package com.luum.michi.app.core

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryCachedViewerStore
import com.luum.michi.app.InMemoryPendingMalAuthStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.OAuthBrowser
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.auth.repository.AniListAuthProvider
import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.repository.MalAuthProvider
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.network.domain.AniListGraphQLRequest
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.KtorAniListGraphQLClient
import com.luum.michi.app.core.session.domain.AniListViewerRepository
import com.luum.michi.app.core.session.domain.SessionManager
import com.luum.michi.app.core.session.domain.SessionState
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.mediaList.InMemoryFileStore
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import com.luum.michi.app.testSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SilentOAuthBrowser : OAuthBrowser {
    override fun open(url: String) = Unit
}

private class FixedViewerRepository(private val viewer: Viewer) : AniListViewerRepository {
    override suspend fun fetchViewer(): NetworkResult<Viewer> = NetworkResult.Success(viewer)
}

class MalSessionIntegrationTest {

    /**
     * 11.2's rule, now exercised with the real MAL provider: a rejected MAL
     * callback is ignored and never closes the AniList session it did not start.
     */
    @Test
    fun anInvalidMalCallbackNeverClosesAnAniListSession() = runBlocking {
        val aniListViewer = Viewer(id = 7, name = "anilist-user")
        val aniListStorage = InMemoryTokenStorage().apply {
            save(
                AuthToken(
                    accessToken = "anilist-token",
                    savedAtEpochSeconds = currentEpochSeconds(),
                    expiresInSeconds = 3600L,
                ),
            )
        }
        val malStorage = InMemoryTokenStorage()
        val activeProvider = InMemoryActiveProviderStore(ProviderId.AniList)
        val cachedViewer = InMemoryCachedViewerStore()
        var malTokenRequests = 0
        val malHttp = MalHttp(
            httpClient = HttpClient(
                MockEngine {
                    malTokenRequests++
                    respond(
                        """{"access_token":"x","refresh_token":"y","expires_in":3600}""",
                        HttpStatusCode.OK,
                    )
                },
            ),
            clientId = "test-client",
            redirectUri = "michi://oauth/callback",
        )
        val pendingStore = InMemoryPendingMalAuthStore(
            PendingMalAuth(
                verifier = "the-verifier",
                state = "expected-state",
                createdAtEpochSeconds = currentEpochSeconds(),
            ),
        )
        val manager = SessionManager(
            providers = listOf(
                AniListAuthProvider(SilentOAuthBrowser(), FixedViewerRepository(aniListViewer)),
                MalAuthProvider(SilentOAuthBrowser(), malHttp, pendingStore),
            ),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            activeProviderStore = activeProvider,
            cachedViewerStore = cachedViewer,
        )
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Authenticated)

        manager.onOAuthCallback("michi://oauth/callback?code=abc&state=wrong")

        val state = manager.state.value
        assertTrue(state is SessionState.Authenticated, "the invalid callback is ignored")
        assertEquals(ProviderId.AniList, state.provider)
        assertEquals(aniListViewer.name, state.viewer.name)
        assertEquals("anilist-token", aniListStorage.load()?.accessToken)
        assertNull(malStorage.load(), "no MAL token is stored")
        assertEquals(0, malTokenRequests, "a bad state never reaches the token endpoint")
        assertNull(pendingStore.pending, "the pending verifier is discarded")
        assertEquals(ProviderId.AniList, activeProvider.load())
    }

    /**
     * The read-only list keeps its +1/editor buttons for 11.4b; those writes go
     * to the AniList GraphQL client, which has no token in a MAL session. The
     * anonymous 401 must not close MAL.
     */
    @Test
    fun anAnonymousAniListWrite401DoesNotCloseTheMalSession() = runBlocking {
        val storage = InMemoryTokenStorage().apply {
            save(
                AuthToken(
                    accessToken = "mal-token",
                    savedAtEpochSeconds = currentEpochSeconds(),
                    expiresInSeconds = 3600L,
                ),
            )
        }
        val provider = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = NetworkResult.Success(
                Viewer(id = 42, name = "mal-user", provider = ProviderId.Mal),
            ),
        )
        val manager = testSessionManager(storage = storage, provider = provider)
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Authenticated)

        var unauthorizedCalls = 0
        val client = KtorAniListGraphQLClient(
            httpClient = HttpClient(
                MockEngine { request ->
                    assertNull(request.headers["Authorization"], "no MAL token on AniList")
                    respond("", HttpStatusCode.Unauthorized)
                },
            ),
            tokenProvider = { manager.validToken(ProviderId.AniList) },
            onUnauthorized = { unauthorizedCalls++ },
        )

        val result = client.execute(AniListGraphQLRequest("mutation X { save }")) { it.toString() }

        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, unauthorizedCalls, "anonymous 401 never closes a session")
        assertTrue(manager.state.value is SessionState.Authenticated)
    }

    @Test
    fun aMalList401ClosesTheMalSession() = runBlocking {
        val storage = InMemoryTokenStorage().apply {
            save(
                AuthToken(
                    accessToken = "mal-token",
                    savedAtEpochSeconds = currentEpochSeconds(),
                    expiresInSeconds = 3600L,
                ),
            )
        }
        val provider = TestAuthProvider(
            id = ProviderId.Mal,
            viewerResult = NetworkResult.Success(
                Viewer(id = 42, name = "mal-user", provider = ProviderId.Mal),
            ),
        )
        val manager = testSessionManager(storage = storage, provider = provider)
        manager.bootstrap()
        assertTrue(manager.state.value is SessionState.Authenticated)

        val loader = MalListLoader(
            http = MalHttp(
                httpClient = HttpClient(
                    MockEngine { respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized) },
                ),
                clientId = "test-client",
                redirectUri = "michi://oauth/callback",
            ),
            tokenProvider = { manager.validToken(ProviderId.Mal) },
            bridge = MediaIdBridgeImpl(FakeGraphQL(emptyList()), InMemoryFileStore()),
            onUnauthorized = { token -> manager.onUnauthorized(token) },
        )

        val result = loader.load(MediaType.ANIME, 42)

        assertTrue(result is NetworkResult.Failure)
        assertTrue(manager.state.value is SessionState.Anonymous)
        assertNull(storage.load())
    }
}
