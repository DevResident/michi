package com.luum.michi.app.core

import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryCachedViewerStore
import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.testLinkedAccountManager
import com.luum.michi.app.testSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun expiring(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds() - 3550L,
    expiresInSeconds = 3600L,
    refreshToken = "refresh-$access",
)

private fun refreshed(access: String) = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
    refreshToken = "refresh-$access",
)

private fun viewer(provider: ProviderId) = Viewer(
    id = if (provider == ProviderId.Mal) 42 else 7,
    name = "user-${provider.value}",
    provider = provider,
    scoreFormat = if (provider == ProviderId.Mal) "POINT_10" else "POINT_10_DECIMAL",
)

class RefreshPerProviderTest {

    @Test
    fun aniListAndMalRefreshesAreIndependentAndSingleFlightPerProvider() = runBlocking {
        val aniListStorage = InMemoryTokenStorage().apply { save(expiring("ani")) }
        val malStorage = InMemoryTokenStorage().apply { save(expiring("mal")) }
        val aniListGate = CompletableDeferred<Unit>()
        val malGate = CompletableDeferred<Unit>()
        val aniList = TestAuthProvider(
            id = ProviderId.AniList,
            viewerResult = NetworkResult.Success(viewer(ProviderId.AniList)),
            refreshResult = NetworkResult.Success(refreshed("ani-new")),
        ).apply { refreshGate = aniListGate }
        val mal = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = NetworkResult.Success(viewer(ProviderId.Mal)),
            refreshResult = NetworkResult.Success(refreshed("mal-new")),
        ).apply { refreshGate = malGate }
        val linked = testLinkedAccountManager(
            InMemoryLinkedAccountStore(
                account = LinkedAccount(
                    provider = ProviderId.Mal,
                    viewer = LinkedViewer(42, "mal", scoreFormat = ScoreFormat.POINT_10),
                ),
            ),
        )
        linked.bootstrap()
        val session = testSessionManager(
            providers = listOf(aniList, mal),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.AniList),
            cachedViewerStore = InMemoryCachedViewerStore(viewer(ProviderId.AniList)),
            linkedAccounts = linked,
        )

        val aniListCaller = async { session.validToken(ProviderId.AniList) }
        val malCaller = async { session.mirrorTokens.mirrorToken(ProviderId.Mal) }
        yield()

        assertEquals(1, aniList.refreshCalls)
        assertEquals(1, mal.refreshCalls)
        // Completing MAL alone must return MAL without waiting for AniList.
        malGate.complete(Unit)
        val malToken = malCaller.await()
        assertEquals("mal-new", malToken?.accessToken)
        assertEquals(1, aniList.refreshCalls, "MAL never blocks or duplicates AniList")

        aniListGate.complete(Unit)
        val aniListToken = aniListCaller.await()
        assertNotNull(aniListToken)
        assertEquals("ani-new", aniListToken.accessToken)
        assertEquals(1, aniList.refreshCalls)
        assertEquals(1, mal.refreshCalls)
    }
}
