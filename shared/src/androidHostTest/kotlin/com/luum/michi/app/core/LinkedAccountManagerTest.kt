package com.luum.michi.app.core

import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.testLinkedAccountManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun freshLinkedToken(access: String = "linked-token") = AuthToken(
    accessToken = access,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun expiringLinkedToken(refresh: String = "refresh") = AuthToken(
    accessToken = "old-linked",
    savedAtEpochSeconds = currentEpochSeconds() - 3550L,
    expiresInSeconds = 3600L,
    refreshToken = refresh,
)

private fun refreshedLinkedToken() = AuthToken(
    accessToken = "new-linked",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun malViewer() = Viewer(
    id = 42,
    name = "mal-user",
    avatarUrl = "https://example.com/avatar.png",
    provider = ProviderId.Mal,
    scoreFormat = "POINT_10",
)

private fun aniListViewer() = Viewer(
    id = 7,
    name = "anilist-user",
    provider = ProviderId.AniList,
    scoreFormat = "POINT_10_DECIMAL",
)

class LinkedAccountManagerTest {

    private fun manager(
        malProvider: TestAuthProvider = TestAuthProvider(id = ProviderId.Mal),
        malStorage: InMemoryTokenStorage = InMemoryTokenStorage(),
        aniListStorage: InMemoryTokenStorage = InMemoryTokenStorage(),
        store: InMemoryLinkedAccountStore = InMemoryLinkedAccountStore(),
        onUnlinked: suspend () -> Unit = {},
    ): Triple<com.luum.michi.app.core.session.domain.LinkedAccountManager, TestAuthProvider, InMemoryTokenStorage> {
        val linked = testLinkedAccountManager(
            providers = listOf(TestAuthProvider(), malProvider),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            store = store,
            viewerFetchers = mapOf(
                ProviderId.AniList to { NetworkResult.Success(aniListViewer()) },
                ProviderId.Mal to { NetworkResult.Success(malViewer()) },
            ),
            onUnlinked = onUnlinked,
        )
        return Triple(linked, malProvider, malStorage)
    }

    @Test
    fun linkingStoresTheTokenAndTheMinimumViewer() = runBlocking {
        val (linked, malProvider, malStorage) = manager()
        val token = freshLinkedToken()

        val result = linked.link(malProvider, token)

        assertTrue(result is NetworkResult.Success)
        assertEquals(token, malStorage.load())
        assertEquals(ProviderId.Mal, linked.linked.value?.provider)
        assertEquals("mal-user", linked.linked.value?.viewer?.name)
        assertEquals("https://example.com/avatar.png", linked.linked.value?.viewer?.avatarUrl)
        assertEquals(ScoreFormat.POINT_10, linked.linked.value?.viewer?.scoreFormat)
        assertTrue(linked.autoCopy.value, "automatic copies start on")
    }

    @Test
    fun validTokenIsScopedToTheLinkedProvider() = runBlocking {
        val (linked, malProvider, _) = manager()
        linked.link(malProvider, freshLinkedToken())

        assertEquals("linked-token", linked.validToken(ProviderId.Mal)?.accessToken)
        assertNull(linked.validToken(ProviderId.AniList), "the other provider never gets this token")
    }

    @Test
    fun concurrentRefreshesShareOneRequest() = runBlocking {
        val (linked, malProvider, _) = manager()
        linked.link(malProvider, expiringLinkedToken())
        val gate = CompletableDeferred<Unit>()
        malProvider.refreshResult = NetworkResult.Success(refreshedLinkedToken())
        malProvider.refreshGate = gate

        val callers = List(6) { async { linked.validToken(ProviderId.Mal) } }
        yield()
        assertEquals(1, malProvider.refreshCalls, "all callers share the same refresh")
        gate.complete(Unit)
        val tokens = callers.awaitAll()

        assertTrue(tokens.all { it?.accessToken == "new-linked" })
        assertEquals(1, malProvider.refreshCalls)
        assertEquals("new-linked", linked.validToken(ProviderId.Mal)?.accessToken)
    }

    @Test
    fun invalidGrantUnlinksAndLeavesANotice() = runBlocking {
        val (linked, malProvider, malStorage) = manager()
        linked.link(malProvider, expiringLinkedToken())
        malProvider.refreshResult = NetworkResult.Failure(NetworkError.Unauthorized(null))

        val token = linked.validToken(ProviderId.Mal)

        assertNull(token)
        assertNull(linked.linked.value)
        assertNull(malStorage.load())
        assertEquals(ProviderId.Mal, linked.lostAccess.value)
        assertFalse(linked.autoCopy.value)
    }

    @Test
    fun aStaleUnauthorizedDoesNotUnlinkANewerToken() = runBlocking {
        val (linked, malProvider, malStorage) = manager()
        val old = freshLinkedToken("old")
        linked.link(malProvider, old)
        malStorage.save(freshLinkedToken("new"))

        linked.onUnauthorized(old)

        assertEquals(ProviderId.Mal, linked.linked.value?.provider)
        assertEquals("new", malStorage.load()?.accessToken)
        assertNull(linked.lostAccess.value)
    }

    @Test
    fun unlinkClearsTheTokenViewerAndAutoCopy() = runBlocking {
        val (linked, malProvider, malStorage) = manager()
        linked.link(malProvider, freshLinkedToken())

        linked.unlink()

        assertNull(linked.linked.value)
        assertNull(malStorage.load())
        assertFalse(linked.autoCopy.value)
        assertNull(linked.lostAccess.value)
    }

    @Test
    fun unlinkRunsTheCleanupCallback() = runBlocking {
        var cleanups = 0
        val (linked, malProvider, _) = manager(onUnlinked = { cleanups++ })
        linked.link(malProvider, freshLinkedToken())

        linked.unlink()

        assertEquals(1, cleanups)    }

    @Test
    fun bootstrapRestoresAPersistedLink() = runBlocking {
        val store = InMemoryLinkedAccountStore(
            account = LinkedAccount(
                provider = ProviderId.Mal,
                viewer = LinkedViewer(
                    id = 42,
                    name = "mal-user",
                    avatarUrl = null,
                    scoreFormat = ScoreFormat.POINT_10,
                ),
            ),
            autoCopy = true,
        )
        val malStorage = InMemoryTokenStorage().apply { save(freshLinkedToken()) }
        val (linked, _, _) = manager(store = store, malStorage = malStorage)

        linked.bootstrap()

        assertEquals("mal-user", linked.linked.value?.viewer?.name)
        assertTrue(linked.autoCopy.value)
    }

    @Test
    fun bootstrapRemovesAnOrphanTokenWithoutAnAccount() = runBlocking {
        val malStorage = InMemoryTokenStorage().apply { save(freshLinkedToken()) }
        val (linked, _, _) = manager(malStorage = malStorage)

        linked.bootstrap()

        assertNull(linked.linked.value)
        assertNull(malStorage.load())
    }

    @Test
    fun bootstrapWithoutATokenKeepsTheLostAccessNotice() = runBlocking {
        val store = InMemoryLinkedAccountStore(
            account = LinkedAccount(
                provider = ProviderId.Mal,
                viewer = LinkedViewer(42, "mal-user", null, ScoreFormat.POINT_10),
            ),
        )
        val (linked, _, _) = manager(store = store)

        linked.bootstrap()

        assertNull(linked.linked.value)
        assertEquals(ProviderId.Mal, linked.lostAccess.value)
        assertEquals(ProviderId.Mal, store.lostAccess)
    }

    @Test
    fun onSessionProviderAuthenticatedUnlinksWhenItIsTheLinkedProvider() = runBlocking {
        val (linked, malProvider, malStorage) = manager()
        linked.link(malProvider, freshLinkedToken())

        linked.onSessionProviderAuthenticated(ProviderId.Mal)

        assertNull(linked.linked.value)
        assertNull(malStorage.load())
    }
}
