package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the **one** non-session account: its token lives in a separate storage
 * namespace, so logout/401/provider switching of the principal never confuses
 * it with the session, and its provider clients can only ever ask for this
 * provider's token.
 *
 * A dead linked token unlinks (never closes the session) and leaves a notice
 * for the account card. It is never allowed to be the same provider as the
 * active session; Settings and [SessionManager] enforce that.
 */
internal class LinkedAccountManager(
    private val providers: List<AuthProvider>,
    private val tokenStorages: Map<ProviderId, TokenStorage>,
    private val store: LinkedAccountStore,
    private val viewerFetchers: Map<ProviderId, suspend (AuthToken) -> NetworkResult<Viewer>>,
    private val refresher: TokenRefreshCoordinator = TokenRefreshCoordinator(),
    private val onUnlinked: suspend () -> Unit = {},
) {
    private val _linked = MutableStateFlow<LinkedAccount?>(null)
    val linked: StateFlow<LinkedAccount?> = _linked.asStateFlow()

    private val _lostAccess = MutableStateFlow<ProviderId?>(null)
    val lostAccess: StateFlow<ProviderId?> = _lostAccess.asStateFlow()

    private val _linkError = MutableStateFlow<NetworkError?>(null)
    val linkError: StateFlow<NetworkError?> = _linkError.asStateFlow()

    private val _autoCopy = MutableStateFlow(false)
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    /**
     * Restores the persisted link and refreshes its token when needed. An orphan
     * token with no viewer/account is removed; a dead token keeps the notice.
     */
    suspend fun bootstrap() {
        _lostAccess.value = store.loadLostAccess()
        _autoCopy.value = store.loadAutoCopy()

        val account = store.load()
        if (account == null) {
            tokenStorages.values.forEach { it.clear() }
            _linked.value = null
            _autoCopy.value = false
            return
        }

        _linked.value = account
        if (validToken(account.provider) == null && tokenStorages[account.provider]?.load() == null) {
            // validToken already unlinked (dead token / invalid grant).
            _linked.value = null
        }
    }

    /**
     * Stores a link callback. The token is saved first so the viewer fetch can
     * use the linked provider's client; if the identity cannot be resolved the
     * token is discarded instead of leaving a half-link.
     */
    suspend fun link(provider: AuthProvider, token: AuthToken): NetworkResult<Unit> {
        val storage = tokenStorages[provider.id]
            ?: return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No linked storage for ${provider.id.value}")),
            )
        val fetcher = viewerFetchers[provider.id]
            ?: return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No viewer fetcher for ${provider.id.value}")),
            )

        if (_linked.value != null || store.load() != null) {
            unlink(lostAccess = false)
        }
        storage.save(token)
        return when (val result = fetcher(token)) {
            is NetworkResult.Failure -> {
                storage.clear()
                _linkError.value = result.error
                result
            }
            is NetworkResult.Success -> {
                val viewer = result.value
                val format = if (provider.id == ProviderId.Mal) {
                    ScoreFormat.POINT_10
                } else {
                    parseScoreFormat(viewer.scoreFormat)
                }
                val account = LinkedAccount(
                    provider = provider.id,
                    viewer = LinkedViewer(
                        id = viewer.id,
                        name = viewer.name,
                        avatarUrl = viewer.avatarUrl,
                        scoreFormat = format,
                    ),
                )
                store.save(account)
                store.clearLostAccess()
                store.saveAutoCopy(true)
                _linked.value = account
                _lostAccess.value = null
                _linkError.value = null
                _autoCopy.value = true
                NetworkResult.Success(Unit)
            }
        }
    }

    /** A callback that never produced a token: leave no stale link attempt. */
    fun onLinkFailed(error: NetworkError) {
        _linkError.value = error
    }

    fun clearLinkError() {
        _linkError.value = null
    }

    fun dismissLostAccess() {
        store.clearLostAccess()
        _lostAccess.value = null
    }

    fun setAutoCopy(enabled: Boolean) {
        store.saveAutoCopy(enabled)
        _autoCopy.value = enabled
    }

    /**
     * The linked token for [provider], refreshed with the same single-flight
     * rules as the session. A provider mismatch always returns null, so a
     * linked MAL client can never send the AniList token (or the reverse).
     */
    suspend fun validToken(provider: ProviderId): AuthToken? {
        val account = _linked.value ?: return null
        if (account.provider != provider) return null
        val storage = tokenStorages[provider] ?: return null
        val token = storage.load() ?: run {
            unlink(lostAccess = true)
            return null
        }
        if (!token.expiresWithin(RefreshMarginSeconds)) return token

        if (token.refreshToken == null) {
            if (!token.isExpired) return token
            unlink(lostAccess = true)
            return null
        }

        return when (
            val outcome = refresher.refresh(
                key = provider,
                token = token,
                loadStored = { storage.load() },
                refresh = { providerFor(provider)?.refresh(token) },
                save = { new -> saveRefreshed(provider, token, new) },
                onInvalidGrant = { unlink(lostAccess = true) },
            )
        ) {
            is RefreshOutcome.Token -> outcome.value
            RefreshOutcome.Unsupported -> if (token.isExpired) {
                unlink(lostAccess = true)
                null
            } else {
                token
            }
            is RefreshOutcome.NetworkFailure -> if (token.isExpired) null else token
            RefreshOutcome.Closed -> null
            RefreshOutcome.Superseded -> storage.load()?.takeIf { !it.isExpired }
        }
    }

    /** A linked-client 401: unlink only when it still carries the stored token. */
    suspend fun onUnauthorized(token: AuthToken) {
        val account = _linked.value ?: return
        val storage = tokenStorages[account.provider] ?: return
        if (storage.load()?.accessToken != token.accessToken) return
        unlink(lostAccess = true)
    }

    /** Logging into the linked provider makes it the session; it cannot be both. */
    suspend fun onSessionProviderAuthenticated(provider: ProviderId) {
        if (_linked.value?.provider == provider) unlink(lostAccess = false)
        if (_lostAccess.value == provider) dismissLostAccess()
    }

    suspend fun unlink(lostAccess: Boolean = false) {
        val provider = _linked.value?.provider ?: store.load()?.provider
        tokenStorages.values.forEach { it.clear() }
        store.clear()
        store.saveAutoCopy(false)
        _linked.value = null
        _autoCopy.value = false
        if (lostAccess && provider != null) {
            store.saveLostAccess(provider)
            _lostAccess.value = provider
        } else {
            store.clearLostAccess()
            _lostAccess.value = null
        }
        try {
            onUnlinked()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Best-effort: a cleanup failure never changes the session.
        }
    }

    private suspend fun saveRefreshed(
        provider: ProviderId,
        old: AuthToken,
        new: AuthToken,
    ): RefreshSaveResult {
        if (_linked.value?.provider != provider) return RefreshSaveResult.Closed
        val storage = tokenStorages[provider] ?: return RefreshSaveResult.Closed
        val current = storage.load() ?: return RefreshSaveResult.Closed
        if (current.accessToken != old.accessToken) return RefreshSaveResult.Superseded
        storage.save(new)
        return RefreshSaveResult.Saved(new)
    }

    private fun providerFor(id: ProviderId): AuthProvider? =
        providers.firstOrNull { it.id == id }

    private companion object {
        const val RefreshMarginSeconds = 60L
    }
}
