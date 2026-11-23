package com.luum.michi.app.core

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.InMemoryActiveProviderStore
import com.luum.michi.app.InMemoryCachedViewerStore
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
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.AniListGraphQLRequest
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.KtorAniListGraphQLClient
import com.luum.michi.app.core.network.repository.createAniListHttpClient
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
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

private fun account(provider: ProviderId, name: String) = LinkedAccount(
    provider = provider,
    viewer = LinkedViewer(
        id = if (provider == ProviderId.Mal) 42 else 7,
        name = name,
        scoreFormat = if (provider == ProviderId.Mal) ScoreFormat.POINT_10 else ScoreFormat.POINT_10_DECIMAL,
    ),
)

private fun viewer(provider: ProviderId, name: String) = Viewer(
    id = if (provider == ProviderId.Mal) 42 else 7,
    name = name,
    provider = provider,
    scoreFormat = if (provider == ProviderId.Mal) "POINT_10" else "POINT_10_DECIMAL",
)

class LinkedTokenIsolationTest {

    @Test
    fun sessionClientsUseOnlyTheFollowedTokenAndMirrorClientsOnlyTheirProvider() = runBlocking {
        val aniListStorage = InMemoryTokenStorage().apply { save(token("anilist-token")) }
        val malStorage = InMemoryTokenStorage().apply { save(token("mal-token")) }
        val linkedStore = InMemoryLinkedAccountStore(account = account(ProviderId.Mal, "mal-user"))
        val linked = testLinkedAccountManager(linkedStore)
        val session = testSessionManager(
            providers = listOf(
                TestAuthProvider(id = ProviderId.AniList),
                TestAuthProvider(id = ProviderId.Mal),
            ),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.AniList),
            cachedViewerStore = InMemoryCachedViewerStore(viewer(ProviderId.AniList, "ani-user")),
            linkedAccounts = linked,
        )
        linked.bootstrap()
        session.bootstrap()

        var aniListAuthorization: String? = null
        val sessionAniList = KtorAniListGraphQLClient(
            httpClient = createAniListHttpClient(
                MockEngine { request ->
                    aniListAuthorization = request.headers["Authorization"]
                    respond("""{"data":{"ok":true}}""", HttpStatusCode.OK)
                },
            ),
            tokenProvider = { session.validToken(ProviderId.AniList) },
        )
        sessionAniList.execute(AniListGraphQLRequest("query X { ok }")) { it.toString() }

        var malAuthorization: String? = null
        val mirrorMal = MalEntryClient(
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
            tokenProvider = { session.mirrorTokens.mirrorToken(ProviderId.Mal) },
        )
        mirrorMal.read(aniListId = 10, type = MediaType.ANIME, idMal = 20)

        assertEquals("Bearer anilist-token", aniListAuthorization)
        assertEquals("Bearer mal-token", malAuthorization)
        assertNull(session.validToken(ProviderId.Mal), "a followed AniList session never lends MAL")
        assertEquals(
            "anilist-token",
            session.mirrorTokens.mirrorToken(ProviderId.AniList)?.accessToken,
        )
    }

    @Test
    fun switchingTheFollowedAccountSwapsTheTokenSeams() = runBlocking {
        val aniListStorage = InMemoryTokenStorage().apply { save(token("anilist-token")) }
        val malStorage = InMemoryTokenStorage().apply { save(token("mal-token")) }
        val linkedStore = InMemoryLinkedAccountStore(account = account(ProviderId.Mal, "mal-user"))
        val linked = testLinkedAccountManager(linkedStore)
        val session = testSessionManager(
            providers = listOf(
                TestAuthProvider(id = ProviderId.AniList),
                TestAuthProvider(id = ProviderId.Mal),
            ),
            tokenStorages = mapOf(
                ProviderId.AniList to aniListStorage,
                ProviderId.Mal to malStorage,
            ),
            activeProviderStore = InMemoryActiveProviderStore(ProviderId.AniList),
            cachedViewerStore = InMemoryCachedViewerStore(viewer(ProviderId.AniList, "ani-user")),
            linkedAccounts = linked,
        )
        linked.bootstrap()
        session.bootstrap()
        session.setFollowed(ProviderId.Mal)

        assertNull(session.validToken(ProviderId.AniList), "the session no longer owns AniList")
        assertEquals("mal-token", session.validToken(ProviderId.Mal)?.accessToken)
        assertEquals("anilist-token", session.mirrorTokens.mirrorToken(ProviderId.AniList)?.accessToken)

        var mirrorAniListAuthorization: String? = null
        val mirrorAniList = KtorAniListGraphQLClient(
            httpClient = createAniListHttpClient(
                MockEngine { request ->
                    mirrorAniListAuthorization = request.headers["Authorization"]
                    respond("""{"data":{"ok":true}}""", HttpStatusCode.OK)
                },
            ),
            tokenProvider = { session.mirrorTokens.mirrorToken(ProviderId.AniList) },
        )
        mirrorAniList.execute(AniListGraphQLRequest("query X { ok }")) { it.toString() }

        assertEquals("Bearer anilist-token", mirrorAniListAuthorization)
    }
}
