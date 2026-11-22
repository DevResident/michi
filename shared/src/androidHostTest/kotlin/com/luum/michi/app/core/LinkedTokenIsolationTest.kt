package com.luum.michi.app.core

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.network.domain.AniListGraphQLRequest
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.KtorAniListGraphQLClient
import com.luum.michi.app.core.network.repository.createAniListHttpClient
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.mediaList.InMemoryFileStore
import com.luum.michi.app.testLinkedAccountManager
import com.luum.michi.app.testSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun token(value: String) = AuthToken(
    accessToken = value,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

class LinkedTokenIsolationTest {

    @Test
    fun sessionAniListAndLinkedMalNeverSendEachOthersToken() = runBlocking {
        val sessionAniList = InMemoryTokenStorage().apply { save(token("anilist-session")) }
        val linkedMal = InMemoryTokenStorage().apply { save(token("mal-linked")) }
        val session = testSessionManager(
            providers = listOf(TestAuthProvider(id = ProviderId.AniList)),
            tokenStorages = mapOf(ProviderId.AniList to sessionAniList),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.AniList),
        )
        val linked = testLinkedAccountManager(
            providers = listOf(TestAuthProvider(id = ProviderId.Mal)),
            tokenStorages = mapOf(ProviderId.Mal to linkedMal),
            store = InMemoryLinkedAccountStore(),
            viewerFetchers = mapOf(
                ProviderId.Mal to {
                    NetworkResult.Success(Viewer(id = 42, name = "mal", provider = ProviderId.Mal))
                },
            ),
        )
        linked.link(TestAuthProvider(id = ProviderId.Mal), token("mal-linked"))

        var aniListAuthorization: String? = null
        val aniListClient = KtorAniListGraphQLClient(
            httpClient = createAniListHttpClient(
                MockEngine { request ->
                    aniListAuthorization = request.headers["Authorization"]
                    respond("""{"data":{"ok":true}}""", HttpStatusCode.OK)
                },
            ),
            tokenProvider = { session.validToken(ProviderId.AniList) },
        )
        aniListClient.execute(AniListGraphQLRequest("query X { ok }")) { it.toString() }

        var malAuthorization: String? = null
        val malClient = MalEntryClient(
            http = MalHttp(
                httpClient = HttpClient(
                    MockEngine { request ->
                        malAuthorization = request.headers["Authorization"]
                        respond(
                            """{"title":"x","my_list_status":{"status":"watching","score":8,"num_episodes_watched":1}}""",
                            HttpStatusCode.OK,
                        )
                    },
                ),
                clientId = "test",
                redirectUri = "michi://oauth/callback",
            ),
            bridge = MediaIdBridgeImpl(FakeGraphQL(emptyList()), InMemoryFileStore()),
            tokenProvider = { linked.validToken(ProviderId.Mal) },
        )
        malClient.read(aniListId = 10, type = MediaType.ANIME, idMal = 20)

        assertEquals("Bearer anilist-session", aniListAuthorization)
        assertEquals("Bearer mal-linked", malAuthorization)
        assertNull(session.validToken(ProviderId.Mal), "the session never lends a MAL token")
        assertNull(linked.validToken(ProviderId.AniList), "the link never lends an AniList token")
    }

    @Test
    fun sessionMalAndLinkedAniListNeverSendEachOthersToken() = runBlocking {
        val sessionMal = InMemoryTokenStorage().apply { save(token("mal-session")) }
        val linkedAniList = InMemoryTokenStorage().apply { save(token("anilist-linked")) }
        val session = testSessionManager(
            providers = listOf(TestAuthProvider(id = ProviderId.Mal)),
            tokenStorages = mapOf(ProviderId.Mal to sessionMal),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.Mal),
        )
        val linked = testLinkedAccountManager(
            providers = listOf(TestAuthProvider(id = ProviderId.AniList)),
            tokenStorages = mapOf(ProviderId.AniList to linkedAniList),
            store = InMemoryLinkedAccountStore(),
            viewerFetchers = mapOf(
                ProviderId.AniList to {
                    NetworkResult.Success(
                        Viewer(
                            id = 7,
                            name = "ani",
                            provider = ProviderId.AniList,
                            scoreFormat = "POINT_10",
                        ),
                    )
                },
            ),
        )
        linked.link(TestAuthProvider(id = ProviderId.AniList), token("anilist-linked"))

        var sessionAniListAuthorization: String? = "unset"
        val sessionAniListClient = KtorAniListGraphQLClient(
            httpClient = createAniListHttpClient(
                MockEngine { request ->
                    sessionAniListAuthorization = request.headers["Authorization"]
                    respond("""{"data":{"ok":true}}""", HttpStatusCode.OK)
                },
            ),
            tokenProvider = { session.validToken(ProviderId.AniList) },
        )
        sessionAniListClient.execute(AniListGraphQLRequest("query X { ok }")) { it.toString() }

        var linkedAniListAuthorization: String? = null
        val linkedAniListClient = KtorAniListGraphQLClient(
            httpClient = createAniListHttpClient(
                MockEngine { request ->
                    linkedAniListAuthorization = request.headers["Authorization"]
                    respond("""{"data":{"ok":true}}""", HttpStatusCode.OK)
                },
            ),
            tokenProvider = { linked.validToken(ProviderId.AniList) },
        )
        linkedAniListClient.execute(AniListGraphQLRequest("query X { ok }")) { it.toString() }

        assertNull(sessionAniListAuthorization, "an anonymous session cannot send the MAL token to AniList")
        assertEquals("Bearer anilist-linked", linkedAniListAuthorization)
        assertNull(session.validToken(ProviderId.AniList), "a MAL session has no AniList token")
        assertNull(linked.validToken(ProviderId.Mal), "the link never lends a MAL token")
    }
}
