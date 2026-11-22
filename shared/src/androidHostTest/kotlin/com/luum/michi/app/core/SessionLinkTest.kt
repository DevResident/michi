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
import com.luum.michi.app.core.session.domain.AccountEvent
import com.luum.michi.app.core.session.domain.AccountEvents
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.PendingLink
import com.luum.michi.app.core.session.domain.SessionState
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.testLinkedAccountManager
import com.luum.michi.app.testSessionManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun freshToken(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun expiringToken(access: String, refresh: String? = null) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds() - 3550L,
    expiresInSeconds = 3600L,
    refreshToken = refresh,
)

private fun expiredToken(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds() - 3700L,
    expiresInSeconds = 3600L,
)

private fun viewer(provider: ProviderId) = Viewer(
    id = if (provider == ProviderId.Mal) 42 else 7,
    name = "user-${provider.value}",
    provider = provider,
    scoreFormat = if (provider == ProviderId.Mal) "POINT_10" else "POINT_10_DECIMAL",
)

private fun linkedViewer(provider: ProviderId) = LinkedViewer(
    id = if (provider == ProviderId.Mal) 42 else 7,
    name = "user-${provider.value}",
    scoreFormat = if (provider == ProviderId.Mal) ScoreFormat.POINT_10 else ScoreFormat.POINT_10_DECIMAL,
)

private fun otherAccount(provider: ProviderId) = LinkedAccount(provider, linkedViewer(provider))

private class Counters {
    var sessionClosed = 0
    var userWipe = 0
}

private class Harness(
    val session: com.luum.michi.app.core.session.domain.SessionManager,
    val linked: com.luum.michi.app.core.session.domain.LinkedAccountManager,
    val sessionAniList: InMemoryTokenStorage,
    val sessionMal: InMemoryTokenStorage,
    val linkedStore: InMemoryLinkedAccountStore,
    val active: InMemoryActiveProviderStore,
    val cached: InMemoryCachedViewerStore,
    val pending: InMemoryPendingLinkStore,
    val aniListProvider: TestAuthProvider,
    val malProvider: TestAuthProvider,
    val events: AccountEvents,
    val counters: Counters,
    val wiped: MutableList<Pair<ProviderId, Int?>> = mutableListOf(),
    val disconnected: MutableList<ProviderId> = mutableListOf(),
    val followedChanges: MutableList<ProviderId> = mutableListOf(),
)

class SessionLinkTest {

    private suspend fun harness(
        withAniList: Boolean = true,
        withMal: Boolean = false,
        withOtherMal: Boolean = false,
        withOtherAniList: Boolean = false,
        malRefresh: NetworkResult<AuthToken>? = null,
        malViewerResult: NetworkResult<Viewer> = NetworkResult.Success(viewer(ProviderId.Mal)),
    ): Harness {
        val sessionAniList = InMemoryTokenStorage().apply {
            if (withAniList) save(freshToken("anilist-token"))
        }
        val sessionMal = InMemoryTokenStorage().apply {
            if (withMal) save(freshToken("mal-token"))
        }
        val linkedStore = InMemoryLinkedAccountStore(
            account = when {
                withOtherMal -> otherAccount(ProviderId.Mal)
                withOtherAniList -> otherAccount(ProviderId.AniList)
                else -> null
            },
        )
        val active = InMemoryActiveProviderStore(
            if (withAniList) ProviderId.AniList else ProviderId.Mal,
        )
        val cached = InMemoryCachedViewerStore(
            if (withAniList) viewer(ProviderId.AniList) else viewer(ProviderId.Mal),
        )
        val pending = InMemoryPendingLinkStore()
        val events = AccountEvents()
        val counters = Counters()
        val aniListProvider = TestAuthProvider(
            id = ProviderId.AniList,
            viewerResult = NetworkResult.Success(viewer(ProviderId.AniList)),
        )
        val malProvider = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = malViewerResult,
            refreshResult = malRefresh,
            callbackResult = { NetworkResult.Success(freshToken("mal-callback")) },
        )
        val linked = testLinkedAccountManager(linkedStore)
        val wiped = mutableListOf<Pair<ProviderId, Int?>>()
        val disconnected = mutableListOf<ProviderId>()
        val followedChanges = mutableListOf<ProviderId>()
        val harness = Harness(
            session = testSessionManager(
                providers = listOf(aniListProvider, malProvider),
                tokenStorages = mapOf(
                    ProviderId.AniList to sessionAniList,
                    ProviderId.Mal to sessionMal,
                ),
                activeProviderStore = active,
                cachedViewerStore = cached,
                pendingLinkStore = pending,
                linkedAccounts = linked,
                otherViewerFetchers = mapOf(
                    ProviderId.AniList to { NetworkResult.Success(viewer(ProviderId.AniList)) },
                    ProviderId.Mal to { malViewerResult },
                ),
                onSessionClosed = { counters.sessionClosed++ },
                onClearUserData = { counters.userWipe++ },
                onClearAccountData = { provider, viewerId -> wiped += provider to viewerId },
                onFollowedChanged = { followedChanges += it },
                onAccountDisconnected = { disconnected += it },
                events = events,
            ),
            linked = linked,
            sessionAniList = sessionAniList,
            sessionMal = sessionMal,
            linkedStore = linkedStore,
            active = active,
            cached = cached,
            pending = pending,
            aniListProvider = aniListProvider,
            malProvider = malProvider,
            events = events,
            counters = counters,
            wiped = wiped,
            disconnected = disconnected,
            followedChanges = followedChanges,
        )
        return harness
    }

    private suspend fun Harness.bootstrap() {
        linked.bootstrap()
        session.bootstrap()
    }

    private suspend fun Harness.connectMal() {
        pending.save(PendingLink(ProviderId.Mal, currentEpochSeconds()))
        session.onOAuthCallback("michi://oauth/callback?code=mal")
    }

    @Test
    fun aSecondAccountKeepsTheFollowedAccountAndDefaultsTheToggleOff() = runBlocking {
        val h = harness()
        h.bootstrap()
        assertTrue(h.session.state.value is SessionState.Authenticated)
        assertEquals(ProviderId.AniList, h.session.followed.value)

        h.connectMal()

        assertEquals(ProviderId.AniList, h.session.followed.value)
        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertEquals("mal-callback", h.sessionMal.load()?.accessToken)
        assertEquals(ProviderId.Mal, h.linked.linked.value?.provider)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(2, h.session.connected.value.size)
        assertEquals(
            listOf(ProviderId.AniList, ProviderId.Mal),
            h.session.connected.value.map { it.provider },
        )
        assertNull(h.pending.pending)
    }

    @Test
    fun setFollowedSwapsViewersWithoutTouchingTokensOrData() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()
        h.linked.setAutoCopy(true)

        h.session.setFollowed(ProviderId.Mal)

        assertEquals(ProviderId.Mal, h.session.followed.value)
        assertEquals(ProviderId.Mal, h.active.load())
        assertEquals("user-mal", (h.session.state.value as SessionState.Authenticated).viewer.name)
        assertEquals(ProviderId.AniList, h.linked.linked.value?.provider)
        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertEquals("mal-callback", h.sessionMal.load()?.accessToken)
        assertTrue(h.linked.autoCopy.value, "a followed switch never changes the toggle")
        assertEquals(0, h.counters.userWipe)
        assertEquals(0, h.counters.sessionClosed)
        assertEquals(listOf(ProviderId.Mal), h.followedChanges)
        assertNull(h.session.validToken(ProviderId.AniList))
        assertEquals("mal-callback", h.session.validToken(ProviderId.Mal)?.accessToken)
        assertEquals("anilist-token", h.session.mirrorTokens.mirrorToken(ProviderId.AniList)?.accessToken)
    }

    @Test
    fun disconnectingTheOtherKeepsTheFollowedToken() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()

        h.session.disconnect(ProviderId.Mal)

        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertNull(h.sessionMal.load())
        assertNull(h.linked.linked.value)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(ProviderId.AniList, h.session.followed.value)
        assertEquals(listOf(ProviderId.Mal), h.disconnected)
        assertEquals(ProviderId.Mal, h.wiped.single().first)
        assertEquals(42, h.wiped.single().second)
    }

    @Test
    fun disconnectingTheFollowedPromotesTheOtherWithoutLogin() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()
        h.linked.setAutoCopy(true)

        h.session.disconnect(ProviderId.AniList)

        assertNull(h.sessionAniList.load())
        assertEquals("mal-callback", h.sessionMal.load()?.accessToken)
        assertEquals(ProviderId.Mal, h.session.followed.value)
        assertEquals(ProviderId.Mal, h.active.load())
        assertEquals("user-mal", (h.session.state.value as SessionState.Authenticated).viewer.name)
        assertNull(h.linked.linked.value)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(listOf(ProviderId.AniList), h.disconnected)
        assertEquals(ProviderId.AniList, h.wiped.single().first)
        assertEquals(7, h.wiped.single().second)
        assertEquals(listOf(ProviderId.Mal), h.followedChanges)
    }

    @Test
    fun disconnectingTheLastAccountClearsEverything() = runBlocking {
        val h = harness()
        h.bootstrap()

        h.session.disconnect(ProviderId.AniList)

        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.sessionAniList.load())
        assertNull(h.sessionMal.load())
        assertNull(h.linked.linked.value)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(1, h.counters.userWipe)
        assertEquals(1, h.counters.sessionClosed)
    }

    @Test
    fun aDeadOtherAccountIsDroppedAndNeverClosesTheSession() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()
        h.sessionMal.save(expiringToken("expired-mal", refresh = "refresh"))
        h.malProvider.refreshResult = NetworkResult.Failure(NetworkError.Unauthorized(null))

        val token = h.session.mirrorTokens.mirrorToken(ProviderId.Mal)

        assertNull(token)
        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertTrue(h.session.state.value is SessionState.Authenticated)
        assertNull(h.linked.linked.value)
        assertEquals(ProviderId.Mal, h.linked.lostAccess.value)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(listOf(ProviderId.Mal), h.disconnected)
    }

    @Test
    fun aDeadFollowedAccountPromotesTheOther() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()
        h.sessionAniList.save(expiredToken("expired-anilist"))

        val token = h.session.validToken(ProviderId.AniList)

        assertNull(token)
        assertNull(h.sessionAniList.load())
        assertEquals(ProviderId.Mal, h.session.followed.value)
        assertEquals("mal-callback", h.sessionMal.load()?.accessToken)
        assertTrue(h.session.state.value is SessionState.Authenticated)
        assertEquals(ProviderId.AniList, h.linked.lostAccess.value)
        assertNull(h.linked.linked.value)
        assertEquals(listOf(ProviderId.AniList), h.disconnected)
        assertEquals(listOf(ProviderId.Mal), h.followedChanges)
    }

    @Test
    fun aDeadLastAccountClosesTheSession() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.sessionAniList.save(expiredToken("expired-anilist"))

        assertNull(h.session.validToken(ProviderId.AniList))

        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.sessionAniList.load())
        assertNull(h.linked.linked.value)
        assertEquals(1, h.counters.sessionClosed)
    }

    @Test
    fun aStaleUnauthorizedTouchesNothing() = runBlocking {
        val h = harness()
        h.bootstrap()

        h.session.onUnauthorized(freshToken("not-stored"))

        assertEquals("anilist-token", h.sessionAniList.load()?.accessToken)
        assertEquals(ProviderId.AniList, h.session.followed.value)
        assertTrue(h.wiped.isEmpty())
    }

    @Test
    fun logoutClearsBothAccounts() = runBlocking {
        val h = harness()
        h.bootstrap()
        h.connectMal()

        h.session.logout()

        assertTrue(h.session.state.value is SessionState.Anonymous)
        assertNull(h.sessionAniList.load())
        assertNull(h.sessionMal.load())
        assertNull(h.linked.linked.value)
        assertFalse(h.linked.autoCopy.value)
        assertEquals(1, h.counters.userWipe)
        assertEquals(1, h.counters.sessionClosed)
    }

    @Test
    fun bootstrapRestoresBothConnectedAccounts() = runBlocking {
        val h = harness(withMal = true, withOtherMal = true)
        h.bootstrap()

        assertEquals(2, h.session.connected.value.size)
        assertEquals(ProviderId.AniList, h.session.followed.value)
        assertEquals(ProviderId.Mal, h.linked.linked.value?.provider)
    }

    @Test
    fun bootstrapClearsATokenWithoutAViewer() = runBlocking {
        val h = harness(withAniList = true, withMal = true, withOtherMal = false)
        h.bootstrap()

        assertEquals(ProviderId.AniList, h.session.followed.value)
        assertNull(h.sessionMal.load())
        assertNull(h.linked.linked.value)
    }

    @Test
    fun bootstrapPromotesTheOtherWhenTheFollowedTokenIsGone() = runBlocking {
        val h = harness(withAniList = false, withMal = true, withOtherMal = true)
        h.bootstrap()

        assertTrue(h.session.state.value is SessionState.Authenticated)
        assertEquals(ProviderId.Mal, h.session.followed.value)
        assertEquals("mal-token", h.sessionMal.load()?.accessToken)
        assertNull(h.linked.linked.value)
    }

    @Test
    fun eventsAreEmittedForTheAccountTransitions() = runBlocking {
        val h = harness()
        val events = mutableListOf<AccountEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.events.events.collect { events += it }
        }
        h.bootstrap()
        h.connectMal()
        h.session.setFollowed(ProviderId.Mal)
        h.session.disconnect(ProviderId.Mal)
        kotlinx.coroutines.yield()
        job.cancel()

        assertTrue(events.any { it is AccountEvent.Connected && it.provider == ProviderId.Mal })
        assertTrue(events.any { it is AccountEvent.FollowedChanged && it.provider == ProviderId.Mal })
        assertTrue(events.any { it is AccountEvent.Disconnected && it.provider == ProviderId.Mal })
    }
}
