package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates the active provider's token with the current `Viewer`.
 *
 * Lifecycle:
 *  - `bootstrap()` is called at app start: load the active provider's token,
 *    refresh it when needed, then emit the cached viewer (and refresh in the
 *    background) or fetch it.
 *  - `onOAuthCallback(uri)` is called after a provider redirects back; the
 *    provider that recognizes the URI owns the new session.
 *  - `logout()` clears every token + cache and returns to `Anonymous`.
 *  - `validToken(provider)` is a client's token provider: it returns the active
 *    token only for the requested provider (a MAL session never leaks its token
 *    to AniList), refreshes it with a 60 s margin, single-flight.
 *  - `onUnauthorized(token)` closes the session only if that token is still
 *    the active one.
 *
 * Lifecycle ops are serialized by a [Mutex] and tagged with a generation. The
 * lock is held only for state changes — network calls run outside it — so
 * logout/OAuth/401 never wait on the network. A write is dropped when a newer
 * op bumped the generation (a slow bootstrap can never resurrect a stale
 * session).
 */
internal class SessionManager(
    private val providers: List<AuthProvider>,
    private val tokenStorages: Map<ProviderId, TokenStorage>,
    private val activeProviderStore: ActiveProviderStore,
    private val cachedViewerStore: CachedViewerStore,
    private val onClearUserData: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The provider whose last login callback failed; cleared on a new login or success. */
    private val _lastLoginError = MutableStateFlow<ProviderId?>(null)
    val lastLoginError: StateFlow<ProviderId?> = _lastLoginError.asStateFlow()

    private val mutex = Mutex()
    private var generation = 0

    /** One refresh at a time, shared by every concurrent caller. */
    private val refreshMutex = Mutex()
    private var refreshInFlight: RefreshEntry? = null

    private data class BootstrapEntry(
        val generation: Int,
        val providerId: ProviderId,
        val provider: AuthProvider,
        val token: AuthToken,
        val cached: Viewer?,
    )

    private data class RefreshEntry(
        val providerId: ProviderId,
        val deferred: CompletableDeferred<RefreshOutcome>,
    )

    private sealed interface RefreshOutcome {
        data class Token(val value: AuthToken) : RefreshOutcome
        data object Unsupported : RefreshOutcome
        data class NetworkFailure(val error: NetworkError) : RefreshOutcome
        data object Closed : RefreshOutcome
        data object Superseded : RefreshOutcome
    }

    private sealed interface SaveResult {
        data class Saved(val token: AuthToken) : SaveResult
        data object Superseded : SaveResult
        data object Closed : SaveResult
    }

    suspend fun bootstrap() {
        val entry = mutex.withLock {
            val gen = ++generation
            val providerId = activeProviderId()
            val provider = providerFor(providerId)
            val stored = tokenStorages[providerId]?.load()
            if (provider == null || stored == null) {
                clearUserData()
                _state.value = SessionState.Anonymous
                return
            }
            BootstrapEntry(gen, providerId, provider, stored, cachedViewerStore.load())
        }

        val token = resolveBootstrapToken(entry) ?: return

        mutex.withLock {
            if (entry.generation != generation) return
            entry.cached?.let {
                _state.value = SessionState.Authenticated(it.copy(provider = entry.providerId))
            }
        }
        refreshViewer(entry.generation, entry.provider, token, keepCachedOnNetworkError = entry.cached != null)
    }

    /**
     * The active token for [provider], refreshed when it is within the margin.
     * Returns null when another provider owns the session, so a client can never
     * send one account's token to a different service. Every concurrent caller
     * for the same provider shares one refresh request.
     */
    suspend fun validToken(provider: ProviderId): AuthToken? {
        val providerId = activeProviderId()
        if (provider != providerId) return null
        val authProvider = providerFor(providerId) ?: return null
        val storage = tokenStorages[providerId] ?: return null
        val token = storage.load() ?: return null
        if (!token.expiresWithin(REFRESH_MARGIN_SECONDS)) return token

        if (token.refreshToken == null) {
            if (!token.isExpired) return token
            onUnauthorized(token)
            return null
        }

        return when (val outcome = refreshSingleFlight(providerId, authProvider, token)) {
            is RefreshOutcome.Token -> outcome.value
            RefreshOutcome.Unsupported -> if (token.isExpired) {
                onUnauthorized(token)
                storage.load()?.takeIf { !it.isExpired }
            } else {
                token
            }
            is RefreshOutcome.NetworkFailure -> if (token.isExpired) null else token
            RefreshOutcome.Closed -> null
            RefreshOutcome.Superseded -> storage.load()?.takeIf { !it.isExpired }
        }
    }

    /** Clears a previous login failure (called when the user starts a login). */
    fun clearLoginError() {
        _lastLoginError.value = null
    }

    suspend fun onOAuthCallback(uri: String) {
        val handled = providers.firstNotNullOfOrNull { provider ->
            provider.handleCallback(uri)?.let { provider to it }
        } ?: return

        val (provider, result) = handled
        when (result) {
            // A failed callback leaves the current state alone: a guest stays
            // a guest (a cancelled MAL login must not trap them on Error).
            is NetworkResult.Failure -> {
                _lastLoginError.value = provider.id
                return
            }
            is NetworkResult.Success -> {
                _lastLoginError.value = null
                val token = result.value
                val gen = mutex.withLock {
                    val g = ++generation
                    var clearedAnotherSession = false
                    tokenStorages.forEach { (id, storage) ->
                        if (id != provider.id && storage.load() != null) {
                            storage.clear()
                            clearedAnotherSession = true
                        }
                    }
                    if (clearedAnotherSession) clearUserData()
                    tokenStorages[provider.id]?.save(token)
                    activeProviderStore.save(provider.id)
                    g
                }
                refreshViewer(gen, provider, token, keepCachedOnNetworkError = false)
            }
        }
    }

    suspend fun logout() = mutex.withLock {
        generation++
        // Only one session should ever exist; clearing every provider is the
        // safest privacy wipe.
        tokenStorages.values.forEach { it.clear() }
        activeProviderStore.clear()
        clearUserData()
        _state.value = SessionState.Anonymous
    }

    /**
     * A mid-session 401: drop the token + cache and go anonymous — but only if
     * [token] is still the active one, so a stale 401 can never close a newer
     * session.
     */
    suspend fun onUnauthorized(token: AuthToken) = mutex.withLock {
        val providerId = activeProviderId()
        val storage = tokenStorages[providerId] ?: return@withLock
        if (storage.load()?.accessToken != token.accessToken) return@withLock
        closeSessionLocked(providerId)
    }

    private suspend fun resolveBootstrapToken(entry: BootstrapEntry): AuthToken? {
        if (!entry.token.isExpired) return entry.token

        if (entry.token.refreshToken == null) {
            onUnauthorized(entry.token)
            return null
        }

        return when (val outcome = refreshSingleFlight(entry.providerId, entry.provider, entry.token)) {
            is RefreshOutcome.Token -> outcome.value
            RefreshOutcome.Closed -> null
            RefreshOutcome.Unsupported -> {
                onUnauthorized(entry.token)
                null
            }
            is RefreshOutcome.NetworkFailure -> {
                // Keep token and data: with a cached viewer enter the shell and
                // retry on the next request; otherwise show the retry screen.
                mutex.withLock {
                    if (entry.generation != generation) return@withLock
                    _state.value = entry.cached?.let {
                        SessionState.Authenticated(it.copy(provider = entry.providerId))
                    } ?: SessionState.Error(outcome.error)
                }
                null
            }
            RefreshOutcome.Superseded -> null
        }
    }

    private suspend fun refreshSingleFlight(
        providerId: ProviderId,
        provider: AuthProvider,
        token: AuthToken,
    ): RefreshOutcome {
        val (entry, leader) = refreshMutex.withLock {
            val current = refreshInFlight
            if (current != null && current.providerId == providerId) {
                current to false
            } else {
                RefreshEntry(providerId, CompletableDeferred()).also {
                    refreshInFlight = it
                } to true
            }
        }
        if (!leader) return entry.deferred.await()

        var outcome: RefreshOutcome = RefreshOutcome.Closed
        try {
            outcome = when (val result = provider.refresh(token)) {
                null -> RefreshOutcome.Unsupported
                is NetworkResult.Success -> {
                    val merged = result.value.copy(
                        refreshToken = result.value.refreshToken ?: token.refreshToken,
                    )
                    when (val saved = saveRefreshed(providerId, token, merged)) {
                        is SaveResult.Saved -> RefreshOutcome.Token(saved.token)
                        SaveResult.Superseded -> RefreshOutcome.Superseded
                        SaveResult.Closed -> RefreshOutcome.Closed
                    }
                }
                is NetworkResult.Failure -> if (isInvalidGrant(result.error)) {
                    onUnauthorized(token)
                    RefreshOutcome.Closed
                } else {
                    RefreshOutcome.NetworkFailure(result.error)
                }
            }
        } finally {
            refreshMutex.withLock {
                if (refreshInFlight === entry) refreshInFlight = null
            }
            entry.deferred.complete(outcome)
        }
        return outcome
    }

    private suspend fun saveRefreshed(
        providerId: ProviderId,
        old: AuthToken,
        new: AuthToken,
    ): SaveResult = mutex.withLock {
        if (providerId != activeProviderId()) return@withLock SaveResult.Closed
        val storage = tokenStorages[providerId] ?: return@withLock SaveResult.Closed
        val current = storage.load() ?: return@withLock SaveResult.Closed
        if (current.accessToken != old.accessToken) return@withLock SaveResult.Superseded
        storage.save(new)
        SaveResult.Saved(new)
    }

    private suspend fun refreshViewer(
        gen: Int,
        provider: AuthProvider,
        token: AuthToken,
        keepCachedOnNetworkError: Boolean,
    ) {
        val result = provider.fetchViewer(token)
        mutex.withLock {
            if (gen != generation) return@withLock
            when (result) {
                is NetworkResult.Success -> {
                    val viewer = result.value.copy(provider = provider.id)
                    cachedViewerStore.save(viewer)
                    _state.value = SessionState.Authenticated(viewer)
                }
                is NetworkResult.Failure -> when (result.error) {
                    is NetworkError.Unauthorized -> closeSessionLocked(provider.id)
                    else -> if (!keepCachedOnNetworkError) {
                        _state.value = SessionState.Error(result.error)
                    }
                }
            }
        }
    }

    /** Mutex held: drop one provider's session and every user-scoped cache. */
    private suspend fun closeSessionLocked(providerId: ProviderId) {
        generation++
        tokenStorages[providerId]?.clear()
        activeProviderStore.clear()
        clearUserData()
        _state.value = SessionState.Anonymous
    }

    /** Drops the cached viewer and every user-scoped file (lists) together. */
    private suspend fun clearUserData() {
        cachedViewerStore.clear()
        onClearUserData()
    }

    private fun providerFor(id: ProviderId): AuthProvider? =
        providers.firstOrNull { it.id == id }

    /** The provider that owns the current session; guests/unknowns are AniList. */
    internal fun activeProvider(): ProviderId = activeProviderId()

    /**
     * The stored provider when it is actually wired (provider + storage);
     * otherwise the first provider (AniList today).
     */
    private fun activeProviderId(): ProviderId {
        val stored = activeProviderStore.load()
        return if (providers.any { it.id == stored } && tokenStorages.containsKey(stored)) {
            stored
        } else {
            providers.firstOrNull()?.id ?: ProviderId.AniList
        }
    }

    private fun isInvalidGrant(error: NetworkError): Boolean = when (error) {
        is NetworkError.Unauthorized -> true
        is NetworkError.Http -> error.body?.contains("invalid_grant", ignoreCase = true) == true
        is NetworkError.GraphQL -> error.messages.any {
            it.contains("invalid_grant", ignoreCase = true)
        }
        else -> false
    }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}
