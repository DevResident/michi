package com.luum.michi.app

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.domain.PendingMalAuthStore
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.core.session.domain.ActiveProviderStore
import com.luum.michi.app.core.session.domain.CachedViewerStore
import com.luum.michi.app.core.session.domain.SessionManager
import com.luum.michi.app.core.session.domain.Viewer
import kotlinx.coroutines.CompletableDeferred

/** Scriptable provider used by the session, list and profile tests. */
internal val MinimalProviderCapabilities = ProviderCapabilities(
    advancedScores = false,
    privateEntries = false,
    hiddenFromStatusLists = false,
    repeatCount = false,
    priority = false,
    notes = false,
    repeatingStatus = false,
    favourites = false,
    notifications = false,
    social = false,
    profileStatistics = false,
    remoteSettings = false,
)

internal class TestAuthProvider(
    override val id: ProviderId = ProviderId.AniList,
    override val label: String = "Test",
    override val isConfigured: Boolean = true,
    override val capabilities: ProviderCapabilities = ProviderCapabilities.AniList,
    var viewerResult: NetworkResult<Viewer> = NetworkResult.Success(Viewer(id = 1, name = "user")),
    var refreshResult: NetworkResult<AuthToken>? = null,
    var callbackResult: (String) -> NetworkResult<AuthToken>? = { null },
) : AuthProvider {
    var openCalls = 0
        private set
    var viewerCalls = 0
        private set
    var refreshCalls = 0
        private set

    var viewerGate: CompletableDeferred<Unit>? = null
    var refreshGate: CompletableDeferred<Unit>? = null

    override fun open() {
        openCalls++
    }

    override suspend fun handleCallback(uri: String): NetworkResult<AuthToken>? =
        callbackResult(uri)

    override suspend fun refresh(token: AuthToken): NetworkResult<AuthToken>? {
        refreshCalls++
        refreshGate?.await()
        return refreshResult
    }

    override suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer> {
        viewerCalls++
        viewerGate?.await()
        return viewerResult.map { it.copy(provider = id) }
    }
}

internal class InMemoryPendingMalAuthStore(
    var pending: PendingMalAuth? = null,
) : PendingMalAuthStore {
    override fun load(): PendingMalAuth? = pending

    override fun save(pending: PendingMalAuth) {
        this.pending = pending
    }

    override fun clear() {
        pending = null
    }
}

internal class InMemoryActiveProviderStore(
    initial: ProviderId = ProviderId.AniList,
) : ActiveProviderStore {
    var provider: ProviderId = initial
        private set
    var clearCalls = 0
        private set

    override fun load(): ProviderId = provider

    override fun save(provider: ProviderId) {
        this.provider = provider
    }

    override fun clear() {
        provider = ProviderId.AniList
        clearCalls++
    }
}

internal class InMemoryCachedViewerStore(initial: Viewer? = null) : CachedViewerStore {
    var stored: Viewer? = initial
        private set

    override fun load(): Viewer? = stored

    override fun save(viewer: Viewer) {
        stored = viewer
    }

    override fun clear() {
        stored = null
    }
}

internal fun testSessionManager(
    providers: List<AuthProvider>,
    tokenStorages: Map<ProviderId, TokenStorage>,
    activeProviderStore: ActiveProviderStore = InMemoryActiveProviderStore(),
    cachedViewerStore: CachedViewerStore = InMemoryCachedViewerStore(),
    onClearUserData: suspend () -> Unit = {},
): SessionManager = SessionManager(
    providers = providers,
    tokenStorages = tokenStorages,
    activeProviderStore = activeProviderStore,
    cachedViewerStore = cachedViewerStore,
    onClearUserData = onClearUserData,
)

internal fun testSessionManager(
    storage: TokenStorage,
    provider: TestAuthProvider = TestAuthProvider(),
    activeProviderStore: ActiveProviderStore = InMemoryActiveProviderStore(),
    cachedViewerStore: CachedViewerStore = InMemoryCachedViewerStore(),
    onClearUserData: suspend () -> Unit = {},
): SessionManager = testSessionManager(
    providers = listOf(provider),
    tokenStorages = mapOf(provider.id to storage),
    activeProviderStore = activeProviderStore,
    cachedViewerStore = cachedViewerStore,
    onClearUserData = onClearUserData,
)
