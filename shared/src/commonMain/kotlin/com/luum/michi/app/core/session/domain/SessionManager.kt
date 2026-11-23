package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every connected account: the followed provider's token/viewer, the other
 * provider's token, and the transitions between them.
 *
 * - `validToken(provider)` is the session-client seam: it only returns the
 *   followed provider's token, so a MAL session never leaks to AniList.
 * - [mirrorTokens] is the mirror-only seam: it resolves the token of any
 *   connected provider, but the mirror clients are constructed bound to one
 *   provider and never ask for the other.
 * - `setFollowed` swaps the viewers without login, without touching tokens,
 *   list caches or the outbox.
 * - A followed 401 promotes the other account; a last-account 401 closes the
 *   session. A dead other account is dropped with a notice.
 *
 * Lifecycle ops are serialized by [Mutex] and tagged with a generation; network
 * calls run outside the lock (except token refresh, which is single-flight per
 * provider in [TokenRefreshCoordinator]).
 */
internal class SessionManager(
    private val providers: List<AuthProvider>,
    private val tokenStorages: Map<ProviderId, TokenStorage>,
    private val activeProviderStore: ActiveProviderStore,
    private val cachedViewerStore: CachedViewerStore,
    private val onClearUserData: suspend () -> Unit = {},
    private val pendingLinkStore: PendingLinkStore? = null,
    private val linkedAccounts: LinkedAccountManager? = null,
    private val onSessionClosed: suspend () -> Unit = {},
    private val onSessionAuthenticated: suspend (ProviderId) -> Unit = {},
    private val refresher: TokenRefreshCoordinator = TokenRefreshCoordinator(),
    private val otherViewerFetchers: Map<ProviderId, suspend (AuthToken) -> NetworkResult<Viewer>> =
        emptyMap(),
    private val onClearAccountData: suspend (ProviderId, Int?) -> Unit = { _, _ -> },
    private val onFollowedChanged: suspend (ProviderId) -> Unit = {},
    private val onAccountDisconnected: suspend (ProviderId) -> Unit = {},
    private val events: AccountEvents = AccountEvents(),
    private val isDebug: Boolean = false,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _connected = MutableStateFlow<List<ConnectedAccount>>(emptyList())
    val connected: StateFlow<List<ConnectedAccount>> = _connected.asStateFlow()

    private val _followed = MutableStateFlow<ProviderId?>(null)
    val followed: StateFlow<ProviderId?> = _followed.asStateFlow()

    /** The provider whose last login callback failed; cleared on a new login or success. */
    private val _lastLoginError = MutableStateFlow<ProviderId?>(null)
    val lastLoginError: StateFlow<ProviderId?> = _lastLoginError.asStateFlow()

    /** The mirror-only token seam. It is not exposed to any other client. */
    val mirrorTokens: MirrorTokenSource = MirrorTokenSource { provider ->
        resolveMirrorToken(provider)
    }

    private val mutex = Mutex()
    private var generation = 0
    private var followedProvider: ProviderId? = null

    private data class BootstrapEntry(
        val generation: Int,
        val providerId: ProviderId,
        val provider: AuthProvider,
        val token: AuthToken,
        val cached: Viewer?,
        val orphanOther: LinkedAccount? = null,
        val lostFollowed: ProviderId? = null,
        val lostFollowedViewerId: Int? = null,
    )

    suspend fun bootstrap() {
        val entry = mutex.withLock {
            val gen = ++generation
            val stored = activeProviderStore.load()
            val storedToken = tokenStorages[stored]?.load()
            val storedUsable = storedToken != null && providerFor(stored) != null
            val storedCached = cachedViewerStore.load()
            val other = linkedAccounts?.linked?.value
            val otherToken = other?.let { tokenStorages[it.provider]?.load() }
            val otherUsable = other != null && otherToken != null &&
                providerFor(other.provider) != null
            val wired = providers.map { it.id }.firstOrNull { tokenStorages[it]?.load() != null }
            val followed = when {
                storedUsable -> stored
                otherUsable -> other.provider
                wired != null -> wired
                else -> null
            }
            if (followed == null) {
                clearAllLocked()
                return@withLock null
            }
            val lostFollowed = stored.takeIf {
                !storedUsable && otherUsable && storedCached?.provider == it
            }
            if (lostFollowed != null) {
                linkedAccounts?.setLostAccess(lostFollowed)
            }

            // A linked record for the followed provider is stale: the token is
            // the followed account's, not a second account.
            if (linkedAccounts?.linked?.value?.provider == followed) {
                linkedAccounts.setOther(null)
                linkedAccounts.setAutoCopy(false)
            }
            // A linked record for an unconfigured provider cannot be used.
            val otherNow = linkedAccounts?.linked?.value
            if (otherNow != null && providerFor(otherNow.provider) == null) {
                tokenStorages[otherNow.provider]?.clear()
                linkedAccounts.setOther(null)
                linkedAccounts.setAutoCopy(false)
                linkedAccounts.setLostAccess(otherNow.provider)
            }
            // A linked record without its token is an orphan: privacy wipe.
            val linkedNow = linkedAccounts?.linked?.value
            val orphanOther = linkedNow?.takeIf { tokenStorages[it.provider]?.load() == null }
            if (orphanOther != null) {
                linkedAccounts.setOther(null)
                linkedAccounts.setAutoCopy(false)
                linkedAccounts.setLostAccess(orphanOther.provider)
            }

            val token = tokenStorages[followed]?.load()
            if (token == null) {
                clearAllLocked()
                return@withLock null
            }
            val provider = providerFor(followed)
            if (provider == null) {
                clearAllLocked()
                return@withLock null
            }
            activeProviderStore.save(followed)
            followedProvider = followed
            _followed.value = followed
            // Any token that is neither the followed nor the other account is an
            // orphan (e.g. a legacy token without a viewer): privacy wipe.
            val otherProvider = linkedAccounts?.linked?.value?.provider
            tokenStorages.forEach { (id, storage) ->
                if (id != followed && id != otherProvider && storage.load() != null) {
                    storage.clear()
                }
            }
            val cached = cachedViewerStore.load()?.takeIf { it.provider == followed }
            if (cached == null) cachedViewerStore.clear()
            if (cached != null) _state.value = SessionState.Authenticated(cached)
            publishConnectedLocked()
            BootstrapEntry(
                generation = gen,
                providerId = followed,
                provider = provider,
                token = token,
                cached = cached,
                orphanOther = orphanOther,
                lostFollowed = lostFollowed,
                lostFollowedViewerId = storedCached?.takeIf { lostFollowed != null }?.id,
            )
        } ?: run {
            onClearUserData()
            onSessionClosed()
            return
        }

        entry.orphanOther?.let { orphan ->
            onClearAccountData(orphan.provider, orphan.viewer.id)
            onAccountDisconnected(orphan.provider)
            events.emit(AccountEvent.LostAccess(orphan.provider))
        }
        entry.lostFollowed?.let { lost ->
            onClearAccountData(lost, entry.lostFollowedViewerId)
            onAccountDisconnected(lost)
            events.emit(AccountEvent.LostAccess(lost))
            events.emit(AccountEvent.FollowedChanged(entry.providerId))
        }
        val token = resolveBootstrapToken(entry) ?: return
        validateOther(entry.generation)
        refreshViewer(
            gen = entry.generation,
            provider = entry.provider,
            token = token,
            keepCachedOnNetworkError = entry.cached != null,
        )
    }

    /** Clears a previous login failure (called when the user starts a login). */
    fun clearLoginError() {
        _lastLoginError.value = null
    }

    /**
     * Starts OAuth for a provider that is not connected yet. Guests use the
     * same pending-link path; the callback decides whether it is a normal
     * login or a second account.
     */
    fun beginLink(providerId: ProviderId) {
        val provider = providers.firstOrNull { it.id == providerId && it.isConfigured } ?: return
        if (followedProvider == providerId) return
        if (linkedAccounts?.linked?.value?.provider == providerId) return
        linkedAccounts?.clearLinkError()
        pendingLinkStore?.save(
            PendingLink(provider = providerId, createdAtEpochSeconds = currentEpochSeconds()),
        )
        provider.open()
    }

    /**
     * A guest's valid callback starts a session; an existing one only accepts a
     * valid pending link for the other provider. An authenticated session is
     * never replaced by an unprompted deep link.
     */
    suspend fun onOAuthCallback(uri: String) {
        val pending = pendingLinkStore?.load()
        if (hasSession()) {
            handleSessionOAuthCallback(uri, pending)
        } else {
            handleGuestOAuthCallback(uri)
        }
    }

    /**
     * A persisted token counts as a session even while bootstrap is still
     * running, so a callback arriving on a cold start cannot replace it.
     */
    private suspend fun hasSession(): Boolean =
        _state.value is SessionState.Authenticated ||
            tokenStorages.values.any { it.load() != null }

    private suspend fun handleGuestOAuthCallback(uri: String) {
        val handled = providers.firstNotNullOfOrNull { provider ->
            provider.handleCallback(uri)?.let { provider to it }
        } ?: run {
            pendingLinkStore?.clear()
            return
        }

        val (provider, result) = handled
        when (result) {
            is NetworkResult.Failure -> {
                pendingLinkStore?.clear()
                _lastLoginError.value = provider.id
            }
            is NetworkResult.Success -> {
                _lastLoginError.value = null
                pendingLinkStore?.clear()
                linkedAccounts?.clearLinkError()
                loginWith(provider, result.value)
            }
        }
    }

    private suspend fun handleSessionOAuthCallback(uri: String, pending: PendingLink?) {
        if (pending == null) {
            // A stray callback must not exchange a code or replace the session.
            val owner = providers.firstOrNull { it.ownsCallback(uri) }
            owner?.discardPending()
            debugLog(
                "OAuth callback ignored" +
                    (owner?.let { " for ${it.id.value}" } ?: "") +
                    ": no pending link",
            )
            return
        }

        val provider = providers.firstOrNull { it.id == pending.provider && it.isConfigured }
        if (provider == null) {
            pendingLinkStore?.clear()
            debugLog(
                "OAuth callback ignored for ${pending.provider.value}: provider is not configured",
            )
            return
        }
        if (!provider.ownsCallback(uri)) {
            debugLog(
                "OAuth callback ignored for ${pending.provider.value}: not that provider's callback",
            )
            return
        }
        if (pending.provider == followedProvider) {
            pendingLinkStore?.clear()
            debugLog(
                "OAuth callback ignored for ${pending.provider.value}: provider is already followed",
            )
            return
        }
        if (!pending.isValid(currentEpochSeconds())) {
            pendingLinkStore?.clear()
            provider.discardPending()
            linkedAccounts?.onLinkFailed(NetworkError.LinkExpired)
            debugLog(
                "OAuth callback ignored for ${pending.provider.value}: pending link expired",
            )
            return
        }

        val result = provider.handleCallback(uri) ?: run {
            pendingLinkStore?.clear()
            debugLog("OAuth callback ignored for ${provider.id.value}: callback cancelled")
            return
        }
        pendingLinkStore?.clear()
        when (result) {
            is NetworkResult.Failure -> linkedAccounts?.onLinkFailed(result.error)
            is NetworkResult.Success -> {
                if (linkedAccounts?.linked?.value?.provider == provider.id) return
                connectOther(provider, result.value)
            }
        }
    }

    private fun debugLog(message: String) {
        if (isDebug) println("[Michi] $message")
    }

    /**
     * The active token for [provider], but only when it is the followed account.
     * Every concurrent caller for the same provider shares one refresh.
     */
    suspend fun validToken(provider: ProviderId): AuthToken? {
        if (provider != currentFollowedProvider()) return null
        return resolveToken(provider)
    }

    /**
     * Follows another already-connected account: swaps viewers, keeps both
     * tokens, every list cache and the outbox.
     */
    suspend fun setFollowed(provider: ProviderId) {
        var newGeneration = 0
        var changed = false
        mutex.withLock {
            val current = followedProvider ?: return@withLock
            if (provider == current) return@withLock
            val other = linkedAccounts?.linked?.value?.takeIf { it.provider == provider }
                ?: return@withLock
            if (tokenStorages[provider]?.load() == null) return@withLock
            val oldViewer = (state.value as? SessionState.Authenticated)?.viewer
                ?: cachedViewerStore.load()
                ?: return@withLock
            val newViewer = other.toViewer()
            newGeneration = ++generation
            activeProviderStore.save(provider)
            followedProvider = provider
            cachedViewerStore.save(newViewer)
            linkedAccounts.setOther(LinkedAccount(current, oldViewer.toLinkedViewer()))
            if (linkedAccounts.lostAccess.value == provider) linkedAccounts.clearLostAccess()
            _state.value = SessionState.Authenticated(newViewer)
            publishConnectedLocked()
            changed = true
        }
        if (!changed) return
        events.emit(AccountEvent.FollowedChanged(provider))
        onFollowedChanged(provider)
        val token = tokenStorages[provider]?.load() ?: return
        refreshViewer(
            gen = newGeneration,
            provider = providerFor(provider) ?: return,
            token = token,
            keepCachedOnNetworkError = true,
        )
    }

    /** User-initiated disconnect: no lost-access notice. */
    suspend fun disconnect(provider: ProviderId) {
        disconnect(provider, lostAccess = false)
    }

    suspend fun logout() {
        publishCleared()
    }

    /**
     * A mid-session 401: disconnect only the account that owns [token]. A stale
     * token that matches neither account is ignored.
     */
    suspend fun onUnauthorized(token: AuthToken) {
        val provider = mutex.withLock {
            tokenStorages.entries.firstOrNull {
                it.value.load()?.accessToken == token.accessToken
            }?.key
        } ?: return
        if (provider == currentFollowedProvider() ||
            linkedAccounts?.linked?.value?.provider == provider
        ) {
            disconnect(provider, lostAccess = true)
        }
    }

    internal fun activeProvider(): ProviderId = followedProvider ?: ProviderId.AniList

    // ---------------------------------------------------------------------
    // Login / connect
    // ---------------------------------------------------------------------

    private suspend fun loginWith(provider: AuthProvider, token: AuthToken) {
        var hadSession = false
        val gen = mutex.withLock {
            val g = ++generation
            hadSession = _state.value is SessionState.Authenticated ||
                tokenStorages.any { it.value.load() != null }
            tokenStorages.forEach { (id, storage) ->
                if (id != provider.id) storage.clear()
            }
            linkedAccounts?.setOther(null)
            linkedAccounts?.setAutoCopy(false)
            linkedAccounts?.clearLostAccess()
            linkedAccounts?.clearLinkError()
            tokenStorages[provider.id]?.save(token)
            activeProviderStore.save(provider.id)
            followedProvider = provider.id
            cachedViewerStore.clear()
            if (hadSession) _state.value = SessionState.Loading
            publishConnectedLocked()
            g
        }
        if (hadSession) {
            onClearUserData()
            onSessionClosed()
        }
        onSessionAuthenticated(provider.id)
        events.emit(AccountEvent.Connected(provider.id))
        refreshViewer(gen, provider, token, keepCachedOnNetworkError = false)
    }

    /** Second account: its token and viewer are added, the followed account stays. */
    private suspend fun connectOther(provider: AuthProvider, token: AuthToken) {
        val fetcher = otherViewerFetchers[provider.id] ?: return
        val viewer = when (val result = fetcher(token)) {
            is NetworkResult.Failure -> {
                linkedAccounts?.onLinkFailed(result.error)
                return
            }
            is NetworkResult.Success -> result.value
        }
        val scoreFormat = if (provider.id == ProviderId.Mal) {
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
                bannerUrl = viewer.bannerUrl,
                scoreFormat = scoreFormat,
            ),
        )
        val connected = mutex.withLock {
            if (_state.value !is SessionState.Authenticated) return@withLock false
            if (followedProvider == provider.id) return@withLock false
            if (linkedAccounts?.linked?.value != null) return@withLock false
            tokenStorages[provider.id]?.save(token)
            linkedAccounts?.setOther(account)
            linkedAccounts?.setAutoCopy(false)
            linkedAccounts?.clearLostAccess()
            linkedAccounts?.clearLinkError()
            publishConnectedLocked()
            true
        }
        if (connected) events.emit(AccountEvent.Connected(provider.id))
    }

    // ---------------------------------------------------------------------
    // Disconnect / clear
    // ---------------------------------------------------------------------

    private suspend fun disconnect(provider: ProviderId, lostAccess: Boolean) {
        var viewerId: Int? = null
        var promoted: ProviderId? = null
        var last = false
        mutex.withLock {
            linkedAccounts?.clearLinkError()
            val wasFollowed = provider == (followedProvider ?: activeProviderStore.load())
            val linked = linkedAccounts?.linked?.value
            viewerId = if (wasFollowed) {
                (state.value as? SessionState.Authenticated)?.viewer?.id
                    ?: cachedViewerStore.load()?.id
            } else {
                linked?.takeIf { it.provider == provider }?.viewer?.id
            }
            tokenStorages[provider]?.clear()
            promoted = if (wasFollowed) {
                linked?.takeIf { it.provider != provider }
                    ?.takeIf { tokenStorages[it.provider]?.load() != null }
                    ?.provider
            } else {
                null
            }
            when {
                wasFollowed && promoted != null -> {
                    val newViewer = checkNotNull(linked).toViewer()
                    val nextProvider = checkNotNull(promoted)
                    generation++
                    followedProvider = nextProvider
                    activeProviderStore.save(nextProvider)
                    cachedViewerStore.save(newViewer)
                    linkedAccounts?.setOther(null)
                    linkedAccounts?.setAutoCopy(false)
                    if (lostAccess) {
                        linkedAccounts?.setLostAccess(provider)
                    } else if (linkedAccounts?.lostAccess?.value == provider) {
                        linkedAccounts?.clearLostAccess()
                    }
                    _state.value = SessionState.Authenticated(newViewer)
                    publishConnectedLocked()
                }
                wasFollowed -> {
                    last = true
                    clearAllLocked()
                }
                else -> {
                    linkedAccounts?.setOther(null)
                    linkedAccounts?.setAutoCopy(false)
                    if (lostAccess) {
                        linkedAccounts?.setLostAccess(provider)
                    } else if (linkedAccounts?.lostAccess?.value == provider) {
                        linkedAccounts?.clearLostAccess()
                    }
                    publishConnectedLocked()
                }
            }
        }
        events.emit(AccountEvent.Disconnected(provider))
        if (!last) {
            onClearAccountData(provider, viewerId)
            if (lostAccess) events.emit(AccountEvent.LostAccess(provider))
            onAccountDisconnected(provider)
            promoted?.let {
                events.emit(AccountEvent.FollowedChanged(it))
                onFollowedChanged(it)
            }
        } else {
            onClearUserData()
            onSessionClosed()
        }
    }

    private suspend fun publishCleared() {
        val connected = mutex.withLock {
            val current = connectedProvidersLocked()
            clearAllLocked()
            current
        }
        connected.forEach { events.emit(AccountEvent.Disconnected(it)) }
        onClearUserData()
        onSessionClosed()
    }

    /** Mutex held: clear every token, viewer, link and preference. */
    private suspend fun clearAllLocked() {
        generation++
        tokenStorages.values.forEach { it.clear() }
        activeProviderStore.clear()
        cachedViewerStore.clear()
        followedProvider = null
        linkedAccounts?.setOther(null)
        linkedAccounts?.setAutoCopy(false)
        linkedAccounts?.clearLostAccess()
        linkedAccounts?.clearLinkError()
        _state.value = SessionState.Anonymous
        publishConnectedLocked()
    }

    // ---------------------------------------------------------------------
    // Bootstrap / viewer
    // ---------------------------------------------------------------------

    private suspend fun validateOther(gen: Int) {
        val other = linkedAccounts?.linked?.value ?: return
        if (other.provider == followedProvider) {
            mutex.withLock { linkedAccounts?.setOther(null) }
            return
        }
        if (tokenStorages[other.provider]?.load() == null) {
            mutex.withLock {
                if (gen == generation && linkedAccounts?.linked?.value?.provider == other.provider) {
                    linkedAccounts?.setOther(null)
                    linkedAccounts?.setAutoCopy(false)
                }
            }
            return
        }
        val token = mirrorTokens.mirrorToken(other.provider)
        if (token == null) {
            val cleared = mutex.withLock {
                if (gen == generation && linkedAccounts?.linked?.value?.provider == other.provider) {
                    linkedAccounts?.setOther(null)
                    linkedAccounts?.setAutoCopy(false)
                    linkedAccounts?.setLostAccess(other.provider)
                    publishConnectedLocked()
                    true
                } else {
                    false
                }
            }
            if (cleared) {
                onClearAccountData(other.provider, other.viewer.id)
                onAccountDisconnected(other.provider)
                events.emit(AccountEvent.LostAccess(other.provider))
            }
        }
    }

    private suspend fun resolveBootstrapToken(entry: BootstrapEntry): AuthToken? {
        if (!entry.token.isExpired) return entry.token
        if (entry.token.refreshToken == null) {
            onUnauthorized(entry.token)
            return null
        }
        return when (
            val outcome = refresher.refresh(
                key = entry.providerId,
                token = entry.token,
                loadStored = { tokenStorages[entry.providerId]?.load() },
                refresh = { entry.provider.refresh(entry.token) },
                save = { new -> saveRefreshed(entry.providerId, entry.token, new) },
                onInvalidGrant = { onUnauthorized(it) },
            )
        ) {
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
            RefreshOutcome.Superseded -> tokenStorages[entry.providerId]
                ?.load()
                ?.takeIf { !it.isExpired }
        }
    }

    private suspend fun refreshViewer(
        gen: Int,
        provider: AuthProvider,
        token: AuthToken,
        keepCachedOnNetworkError: Boolean,
    ) {
        val result = provider.fetchViewer(token)
        val unauthorized = mutex.withLock {
            if (gen != generation) return
            when (result) {
                is NetworkResult.Success -> {
                    if (followedProvider != provider.id) return
                    val viewer = result.value.copy(provider = provider.id)
                    cachedViewerStore.save(viewer)
                    _state.value = SessionState.Authenticated(viewer)
                    publishConnectedLocked()
                    false
                }
                is NetworkResult.Failure -> when (result.error) {
                    is NetworkError.Unauthorized -> true
                    else -> {
                        if (!keepCachedOnNetworkError) {
                            _state.value = SessionState.Error(result.error)
                        }
                        false
                    }
                }
            }
        }
        if (unauthorized) onUnauthorized(token)
    }

    // ---------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------

    private suspend fun resolveMirrorToken(provider: ProviderId): AuthToken? {
        val valid = provider == currentFollowedProvider() ||
            linkedAccounts?.linked?.value?.provider == provider
        if (!valid) return null
        return resolveToken(provider)
    }

    /**
     * The active token for any connected provider, refreshed with the same
     * single-flight rules. Only [mirrorTokens] calls this.
     */
    private suspend fun resolveToken(provider: ProviderId): AuthToken? {
        val storage = tokenStorages[provider] ?: return null
        val token = storage.load() ?: return null
        if (!token.expiresWithin(REFRESH_MARGIN_SECONDS)) return token

        if (token.refreshToken == null) {
            if (!token.isExpired) return token
            onUnauthorized(token)
            return null
        }

        return when (
            val outcome = refresher.refresh(
                key = provider,
                token = token,
                loadStored = { storage.load() },
                refresh = { providerFor(provider)?.refresh(token) },
                save = { new -> saveRefreshed(provider, token, new) },
                onInvalidGrant = { onUnauthorized(it) },
            )
        ) {
            is RefreshOutcome.Token -> outcome.value
            RefreshOutcome.Unsupported -> if (token.isExpired) {
                onUnauthorized(token)
                null
            } else {
                token
            }
            is RefreshOutcome.NetworkFailure -> if (token.isExpired) null else token
            RefreshOutcome.Closed -> null
            RefreshOutcome.Superseded -> storage.load()?.takeIf { !it.isExpired }
        }
    }

    private suspend fun saveRefreshed(
        providerId: ProviderId,
        old: AuthToken,
        new: AuthToken,
    ): RefreshSaveResult = mutex.withLock {
        val storage = tokenStorages[providerId] ?: return@withLock RefreshSaveResult.Closed
        val current = storage.load() ?: return@withLock RefreshSaveResult.Closed
        if (current.accessToken != old.accessToken) return@withLock RefreshSaveResult.Superseded
        storage.save(new)
        RefreshSaveResult.Saved(new)
    }

    // ---------------------------------------------------------------------
    // State helpers
    // ---------------------------------------------------------------------

    private fun publishConnectedLocked() {
        _followed.value = followedProvider
        _connected.value = buildList {
            val followed = followedProvider
            val viewer = (state.value as? SessionState.Authenticated)?.viewer
            if (followed != null && viewer != null && viewer.provider == followed) {
                add(
                    ConnectedAccount(
                        provider = followed,
                        viewerId = viewer.id,
                        name = viewer.name,
                        avatarUrl = viewer.avatarUrl,
                        bannerUrl = viewer.bannerUrl,
                        scoreFormat = parseScoreFormat(viewer.scoreFormat),
                    ),
                )
            }
            linkedAccounts?.linked?.value?.let { other ->
                if (other.provider != followed) {
                    add(
                        ConnectedAccount(
                            provider = other.provider,
                            viewerId = other.viewer.id,
                            name = other.viewer.name,
                            avatarUrl = other.viewer.avatarUrl,
                            bannerUrl = other.viewer.bannerUrl,
                            scoreFormat = other.viewer.scoreFormat,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun currentFollowedProvider(): ProviderId? {
        followedProvider?.let { return it }
        val stored = activeProviderStore.load()
        return stored.takeIf { tokenStorages[it]?.load() != null }
    }

    private fun isConnectedLocked(provider: ProviderId): Boolean =
        provider == followedProvider || linkedAccounts?.linked?.value?.provider == provider

    private fun connectedProvidersLocked(): List<ProviderId> = buildList {
        followedProvider?.let { add(it) }
        linkedAccounts?.linked?.value?.provider?.let { if (it != followedProvider) add(it) }
    }

    private fun providerFor(id: ProviderId): AuthProvider? =
        providers.firstOrNull { it.id == id }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}
