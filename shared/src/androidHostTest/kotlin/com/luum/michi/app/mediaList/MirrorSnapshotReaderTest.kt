package com.luum.michi.app.mediaList

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.MirrorTokenSource
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import com.luum.michi.app.mediaList.repository.mirror.MirrorSnapshotReader
import com.luum.michi.app.testLinkedAccountManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorSnapshotReaderTest {

    @Test
    fun aniListPrimaryKeepsTheMirrorFieldsFromAFreshRead() = runBlocking {
        val anime = AniListJson.parseToJsonElement(
            """{"MediaListCollection":{"lists":[{"isCustomList":false,"entries":[{
              "id":1,"status":"CURRENT","score":85,"progress":3,"updatedAt":123,
              "priority":3,"notes":"note","repeat":2,"private":true,
              "startedAt":{"year":2024,"month":5,"day":6},
              "media":{"id":10,"idMal":20,"format":"TV","episodes":12,
                "title":{"romaji":"Test","english":null,"native":null,"userPreferred":"Test"},
                "coverImage":{"large":"https://example.com/a.jpg","medium":null,"color":null}}
            }]}]}}""",
        )
        val empty = AniListJson.parseToJsonElement("""{"MediaListCollection":{"lists":[]}}""")
        val client = FakeGraphQL(listOf(anime, empty))
        val bridge = MediaIdBridgeImpl(FakeGraphQL(emptyList()), InMemoryFileStore())
        val malHttp = MalHttp(
            httpClient = HttpClient(MockEngine { error("MAL should not be read") }),
            clientId = "test",
            redirectUri = "michi://oauth/callback",
        )
        val pageMalLoader = MalListLoader(
            http = malHttp,
            tokenProvider = { null },
            bridge = bridge,
        )
        val linked = testLinkedAccountManager(InMemoryLinkedAccountStore())
        val reader = MirrorSnapshotReader(
            sessionProvider = { ProviderId.AniList },
            sessionUserId = { 7 },
            sessionScoreFormat = { ScoreFormat.POINT_100 },
            sessionAniListClient = client,
            sessionMalLoader = pageMalLoader,
            linkedAccounts = linked,
            linkedAniListClient = FakeGraphQL(emptyList()),
            mirrorTokens = com.luum.michi.app.core.session.domain.MirrorTokenSource { null },
            malHttp = malHttp,
            bridge = bridge,
            onUnauthorized = {},
        )

        val result = reader.readPrimary()

        assertTrue(result is com.luum.michi.app.core.network.domain.NetworkResult.Success)
        val entry = result.value.entries.single()
        assertEquals(ProviderId.AniList, result.value.provider)
        assertEquals(20, entry.media.malId)
        assertEquals(10, entry.media.aniListId)
        assertEquals("https://example.com/a.jpg", entry.media.cover?.large)
        assertEquals("note", entry.entry.notes)
        assertEquals(2, entry.entry.repeat)
        assertTrue(entry.entry.isPrivate)
        assertEquals(3, entry.entry.priority)
        assertEquals(ScoreFormat.POINT_100, entry.entry.scoreFormat)
        assertEquals(2024, entry.entry.startedAt?.year)
        assertEquals(5, entry.entry.startedAt?.month)
    }

    @Test
    fun twoSimultaneousMalUnauthorizedResponsesNotifyOnce() = runBlocking {
        var unauthorizedCalls = 0
        val token = AuthToken(
            accessToken = "mal-token",
            savedAtEpochSeconds = currentEpochSeconds(),
            expiresInSeconds = 3600L,
        )
        val malHttp = MalHttp(
            httpClient = HttpClient(
                MockEngine { respond("", HttpStatusCode.Unauthorized) },
            ),
            clientId = "test",
            redirectUri = "michi://oauth/callback",
        )
        val linked = testLinkedAccountManager(
            InMemoryLinkedAccountStore(
                account = LinkedAccount(
                    provider = ProviderId.Mal,
                    viewer = LinkedViewer(42, "mal", scoreFormat = ScoreFormat.POINT_10),
                ),
            ),
        ).apply { bootstrap() }
        val bridge = MediaIdBridgeImpl(FakeGraphQL(emptyList()), InMemoryFileStore())
        val reader = MirrorSnapshotReader(
            sessionProvider = { ProviderId.AniList },
            sessionUserId = { 7 },
            sessionScoreFormat = { ScoreFormat.POINT_100 },
            sessionAniListClient = FakeGraphQL(emptyList()),
            sessionMalLoader = MalListLoader(
                http = malHttp,
                tokenProvider = { null },
                bridge = bridge,
            ),
            linkedAccounts = linked,
            linkedAniListClient = FakeGraphQL(emptyList()),
            mirrorTokens = MirrorTokenSource { token },
            malHttp = malHttp,
            bridge = bridge,
            onUnauthorized = { unauthorizedCalls++ },
        )

        val result = reader.readLinked()

        assertTrue(result is NetworkResult.Failure)
        assertTrue(result.error is NetworkError.Unauthorized)
        assertEquals(1, unauthorizedCalls, "both list 401s must notify the session once")
    }
}
