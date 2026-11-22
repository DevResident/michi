package com.luum.michi.app.core

import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryCachedViewerStore
import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.InMemoryPendingLinkStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.PendingLink
import com.luum.michi.app.core.session.domain.SessionState
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.testLinkedAccountManager
import com.luum.michi.app.testSessionManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sessionToken(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun expiringSessionToken(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds() - 3550L,
    expiresInSeconds = 3600L,
    refreshToken = "refresh",
)

private fun linkedViewer(provider: ProviderId) = Viewer(
    id = if (provider == ProviderId.Mal) 42 else 7,
    name = "linked-${provider.value}",
    provider = provider,
    scoreFormat = if (provider == ProviderId.Mal) "POINT_10" else "POINT_10_DECIMAL",
)

class SessionLinkTest {

    private class Harness(
        val session: com.luum.michi.app.core.session.domain.SessionManager,
        val linked: com.luum.michi.app.core.session.domain.LinkedAccountManager,
        val sessionAniList: InMemoryTokenStorage,
        val sessionMal: InMemoryTokenStorage,
        val linkedAniList: InMemoryTokenStorage,
        val linkedMal: InMemoryTokenStorage,
        val active: InMemoryActiveProviderStore,
        val cached: InMemoryCachedViewerStore,
        val pending: InMemoryPendingLinkStore,
        val aniListProvider: TestAuthProvider,
        val malProvider: TestAuthProvider,
    )

    private suspend fun harness(
        initialProvider: ProviderId = ProviderId.AniList,
        withAniListSession: Boolean = true,
        linkMal: Boolean = true,
        malRefresh: NetworkResult<AuthToken>? = null,
        malCallback: (String) -> NetworkResult<AuthToken>? = { NetworkResult.Success(sessionToken("mal-token")) },
        malViewerResult: NetworkResult<Viewer> = NetworkResult.Success(linkedViewer(ProviderId.Mal)),
    ): Harness {
        val sessionAniList = InMemoryTokenStorage()
        val sessionMal = InMemoryTokenStorage()
        if (withAniListSession) sessionAniList.save(sessionToken("anilist-token"))
        if (initialProvider == ProviderId.Mal) sessionMal.save(sessionToken("mal-session-token"))

        val linkedAniList = InMemoryTokenStorage()
        val linkedMal = InMemoryTokenStorage()
        val active = InMemoryActiveProviderStore(initialProvider)
        val cached = InMemoryCachedViewerStore()
        val pending = InMemoryPendingLinkStore()

        val aniListProvider = TestAuthProvider(
            id = ProviderId.AniList,
            viewerResult = NetworkResult.Success(
                Viewer(id = 1, name = "ani-user", provider = ProviderId.AniList),
            ),
        )
        val malProvider = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = malViewerResult,
            refreshResult = malRefresh,
            callbackResult = malCallback,
        )
        val providers = listOf(aniListProvider, malProvider)

        val linkedStore = InMemoryLinkedAccountStore()
        val linkedManager = testLinkedAccountManager(
            providers = providers,
            tokenStorages = mapOf(
                ProviderId.AniList to linkedAniList,
                ProviderId.Mal to linkedMal,
            ),
            store = linkedStore,
            viewerFetchers = mapOf(
                ProviderId.AniList to { NetworkResult.Success(linkedViewer(ProviderId.AniList)) },
                ProviderId.Mal to { NetworkResult.Success(linkedViewer(ProviderId.Mal)) },
            ),
        )

        val sessionManager = testSessionManager(
            providers = providers,
            tokenStorages = mapOf(
                ProviderId.AniList to sessionAniList,
                ProviderId.Mal to sessionMal,
            ),
            activeProviderStore = active,
            cachedViewerStore = cached,
            pendingLinkStore = pending,
            linkedAccounts = linkedManager,
            onSessionClosed = { linkedManager.unlink() },
            onSessionAuthenticated = { provider ->
                linkedManager.onSessionProviderAuthenticated(provider)
            },
        )

        if (linkMal) {
            runBlocking {
                linkedManager.link(malProvider, sessionToken("linked-mal-token"))
            }
        }
        return Harness(
            session = sessionManager,
            linked = linkedManager,
            sessionAniList = sessionAniList,
            sessionMal = sessionMal,
            linkedAniList = linkedAniList,
            linkedMal = linkedMal,
            active = active,
            cached = cached,
            pending = pending,
            aniListProvider = aniListProvider,
            malProvider = malProvider,
        )
    }

    @Test
    fun aPendingLinkCallbackStoresTheLinkAndLeavesTheSessionAlone() = runBlocking {
        val h = harness()
        h.session.bootstrap()
        assertTrue(h.session.state.value is SessionState.Authenticated)
        h.pending.save(PendingLink(ProviderId.Mal, currentEpochSeconds()))
        val beforeViewer = h.cached.stored

        h.session.onOAuthCallback("michi://oauth/callback?code=abc")

        val state = h.session.state.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals(ProviderId.AniList, state.provider)
        assertEquals("ani-user", state.viewer.name)
        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertNull(h.sessionMal.load(), "linking never writes the session's MAL storage")
        assertEquals(ProviderId.AniList, h.active.load())
        assertEquals(beforeViewer, h.cached.stored)
        assertNull(h.pending.pending)
        assertEquals(ProviderId.Mal, h.linked.linked.value?.provider)
        assertEquals("mal-token", h.linkedMal.load()?.accessToken)
    }

    @Test
    fun aLoginWithTheLinkedProviderUnlinksIt() = runBlocking {
        val h = harness()
        h.session.bootstrap()
        assertEquals(ProviderId.Mal, h.linked.linked.value?.provider)

        h.session.onOAuthCallback("michi://oauth/callback?code=abc")

        assertEquals(ProviderId.Mal, h.active.load())
        assertEquals("mal-token", h.sessionMal.load()?.accessToken)
        assertNull(h.sessionAniList.load())
        assertNull(h.linked.linked.value)
        assertNull(h.linkedMal.load())
    }

    @Test
    fun aLinkedInvalidGrantUnlinksButDoesNotCloseTheSession() = runBlocking {
        val h = harness()
        h.session.bootstrap()
        assertTrue(h.session.state.value is SessionState.Authenticated)
        h.linkedMal.save(
            AuthToken(
                accessToken = "expired-linked",
                savedAtEpochSeconds = currentEpochSeconds() - 3550L,
                expiresInSeconds = 3600L,
                refreshToken = "refresh",
            ),
        )
        h.malProvider.refreshResult = NetworkResult.Failure(NetworkError.Unauthorized(null))

        val token = h.linked.validToken(ProviderId.Mal)

        assertNull(token)
        assertNull(h.linked.linked.value)
        assertEquals(ProviderId.Mal, h.linked.lostAccess.value)
        assertTrue(
            h.session.state.value is SessionState.Authenticated,
            "a dead link never closes the principal session",
        )
        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
    }

    @Test
    fun logoutUnlinksTheLinkedAccount() = runBlocking {
        val h = harness()
        h.session.bootstrap()
        assertEquals(ProviderId.Mal, h.linked.linked.value?.provider)

        h.session.logout()

        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.linked.linked.value)
        assertNull(h.linkedMal.load())
    }

    @Test
    fun aSession401UnlinksTheLinkedAccount() = runBlocking {
        val h = harness()
        h.session.bootstrap()
        val token = h.sessionAniList.load()!!

        h.session.onUnauthorized(token)

        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.linked.linked.value)
        assertNull(h.linkedMal.load())
    }

    @Test
    fun anInvalidGrantInTheSessionUnlinksTheLinkedAccount() = runBlocking {
        val h = harness(
            initialProvider = ProviderId.Mal,
            withAniListSession = false,
            linkMal = false,
            malRefresh = NetworkResult.Failure(NetworkError.Unauthorized(null)),
        )
        h.sessionMal.save(expiringSessionToken("expired-mal"))
        runBlocking {
            h.linked.link(h.aniListProvider, sessionToken("linked-anilist-token"))
        }
        h.session.bootstrap()

        val token = h.session.validToken(ProviderId.Mal)

        assertNull(token)
        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.linked.linked.value)
        assertNull(h.linkedAniList.load())
    }

    @Test
    fun bootstrapWithoutASessionUnlinksAnOrphanLink() = runBlocking {
        val h = harness(withAniListSession = false)
        val linkedStore = InMemoryLinkedAccountStore(
            account = LinkedAccount(
                provider = ProviderId.Mal,
                viewer = LinkedViewer(42, "mal-user", null, ScoreFormat.POINT_10),
            ),
        )
        // Build a manager that already has a persisted link but no session.
        val linked = testLinkedAccountManager(
            providers = listOf(h.aniListProvider, h.malProvider),
            tokenStorages = mapOf(
                ProviderId.AniList to h.linkedAniList,
                ProviderId.Mal to h.linkedMal,
            ),
            store = linkedStore,
            viewerFetchers = mapOf(
                ProviderId.Mal to { NetworkResult.Success(linkedViewer(ProviderId.Mal)) },
            ),
        )
        h.linkedMal.save(sessionToken("linked-token"))
        val session = testSessionManager(
            providers = listOf(h.aniListProvider, h.malProvider),
            tokenStorages = mapOf(
                ProviderId.AniList to h.sessionAniList,
                ProviderId.Mal to h.sessionMal,
            ),
            activeProviderStore = h.active,
            cachedViewerStore = h.cached,
            onSessionClosed = { linked.unlink() },
        )

        session.bootstrap()

        assertTrue(session.state.value is SessionState.Anonymous)
        assertNull(linked.linked.value)
        assertNull(h.linkedMal.load())
    }
}
