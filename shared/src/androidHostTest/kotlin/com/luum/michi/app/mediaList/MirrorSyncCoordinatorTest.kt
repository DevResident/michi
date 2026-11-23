package com.luum.michi.app.mediaList

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MirrorOutbox
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.MirrorTokenSource
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import com.luum.michi.app.mediaList.repository.mirror.MirrorController
import com.luum.michi.app.mediaList.repository.mirror.MirrorSnapshotReader
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncCoordinator
import com.luum.michi.app.testLinkedAccountManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun token() = AuthToken(
    accessToken = "mirror-token",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun aniListPage(progress: Int) = AniListJson.parseToJsonElement(
    """{"MediaListCollection":{"lists":[{"isCustomList":false,"entries":[{
      "id":1,"status":"COMPLETED","score":90,"progress":$progress,"updatedAt":100,
      "priority":0,"notes":null,"repeat":0,"private":false,
      "completedAt":{"year":2026,"month":1,"day":2},
      "media":{"id":10,"idMal":20,"format":"TV","episodes":13,
        "title":{"romaji":"Test","english":null,"native":null,"userPreferred":"Test"},
        "coverImage":{"large":null,"medium":null,"color":null}}
    }]}]}}""",
)

private val emptyPage = AniListJson.parseToJsonElement("""{"MediaListCollection":{"lists":[]}}""")

private fun malListPage(progress: Int, totalEpisodes: Int? = null): String {
    val total = totalEpisodes?.let { ""","num_episodes":$it""" }.orEmpty()
    return """
{"data":[{"node":{"id":20,"title":"Test","main_picture":{"medium":"https://example.com/m.jpg","large":"https://example.com/l.jpg"}$total},
"list_status":{"status":"completed","score":9,"num_episodes_watched":$progress,
"is_rewatching":false,"updated_at":"2026-11-22T09:05:00+00:00","priority":0,
"finish_date":"2026-01-02","num_times_rewatched":0,"comments":""}}],"paging":{}}
""".trimIndent()
}

private fun malDetail(progress: Int) = """
{"id":20,"title":"Test","num_episodes":12,
"my_list_status":{"status":"completed","score":9,"num_episodes_watched":$progress,
"is_rewatching":false,"updated_at":"2026-11-22T09:05:00+00:00"}}
""".trimIndent()

private fun coordinator(
    listProgress: Int,
    primaryProgress: Int,
    listTotalEpisodes: Int? = null,
    malPaths: MutableList<String>? = null,
): MirrorSyncCoordinator {
    val malHttp = MalHttp(
        httpClient = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                malPaths?.add(path)
                val body = when {
                    path.endsWith("/users/@me/animelist") ->
                        malListPage(listProgress, listTotalEpisodes)
                    path.endsWith("/users/@me/mangalist") -> """{"data":[],"paging":{}}"""
                    path.contains("/anime/20") -> malDetail(listProgress)
                    else -> error("unexpected MAL path $path")
                }
                respond(body, HttpStatusCode.OK)
            },
        ),
        clientId = "test",
        redirectUri = "michi://oauth/callback",
    )
    val bridge = MediaIdBridgeImpl(FakeGraphQL(emptyList()), InMemoryFileStore())
    val linked = testLinkedAccountManager(
        InMemoryLinkedAccountStore(
            account = LinkedAccount(
                provider = ProviderId.Mal,
                viewer = LinkedViewer(42, "mal", scoreFormat = ScoreFormat.POINT_10),
            ),
        ),
    ).apply { bootstrap() }
    val reader = MirrorSnapshotReader(
        sessionProvider = { ProviderId.AniList },
        sessionUserId = { 7 },
        sessionScoreFormat = { ScoreFormat.POINT_100 },
        sessionAniListClient = FakeGraphQL(
            listOf(aniListPage(primaryProgress), emptyPage),
        ),
        sessionMalLoader = MalListLoader(
            http = malHttp,
            tokenProvider = { null },
            bridge = bridge,
        ),
        linkedAccounts = linked,
        linkedAniListClient = FakeGraphQL(emptyList()),
        mirrorTokens = MirrorTokenSource { token() },
        malHttp = malHttp,
        bridge = bridge,
        onUnauthorized = {},
    )
    val controller = MirrorController(
        outbox = MirrorOutbox(InMemoryFileStore()),
        linkedAccounts = linked,
        writers = emptyMap(),
        followedProvider = { ProviderId.AniList },
        sourceScoreFormat = { ScoreFormat.POINT_100 },
        isConnected = { it == ProviderId.AniList || it == ProviderId.Mal },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )
    return MirrorSyncCoordinator(reader, linked, controller)
}

class MirrorSyncCoordinatorTest {

    @Test
    fun theDestinationTotalRemovesTheFalseProgressRow() = runBlocking {
        val result = coordinator(listProgress = 12, primaryProgress = 13).loadPreview()

        assertTrue(result is NetworkResult.Success)
        assertTrue(result.value.updates.isEmpty())
    }

    @Test
    fun aGenuineIncreaseIsCappedToTheDestinationTotal() = runBlocking {
        val result = coordinator(listProgress = 5, primaryProgress = 13).loadPreview()

        assertTrue(result is NetworkResult.Success)
        val row = result.value.updates.single()
        assertEquals(setOf(MirrorField.PROGRESS), row.changedFields)
        assertEquals(12, row.after.progress)
        assertTrue(!row.regression)
    }

    @Test
    fun malListTotalsAvoidTheWorkDetailAndKeepTheSameDiff() = runBlocking {
        val paths = mutableListOf<String>()
        val withTotals = coordinator(
            listProgress = 5,
            primaryProgress = 13,
            listTotalEpisodes = 12,
            malPaths = paths,
        ).loadPreview()
        val fallback = coordinator(listProgress = 5, primaryProgress = 13).loadPreview()

        assertEquals(fallback, withTotals)
        assertTrue(paths.none { it.contains("/anime/") })

        assertTrue(withTotals is NetworkResult.Success)
        val row = withTotals.value.updates.single()
        assertEquals(setOf(MirrorField.PROGRESS), row.changedFields)
        assertEquals(12, row.after.progress)
    }
}
