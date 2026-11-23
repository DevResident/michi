package com.luum.michi.app.mediaList

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.favourite.repository.UnsupportedFavouriteRepository
import com.luum.michi.app.core.mal.domain.malOnlyMediaId
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorTranslator
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MediaListEntryRepositoryImpl
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.mediaList.repository.mal.MalMediaListEntryRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class EmptyBridge : MediaIdBridge {
    override suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>> = NetworkResult.Success(emptyMap())

    override suspend fun resolveMal(aniListId: Int): NetworkResult<MalRef?> =
        NetworkResult.Success(null)

    override suspend fun rememberMalRef(aniListId: Int, ref: MalRef) = Unit

    override suspend fun cachedIdMap(type: MediaType): Map<Int, Int> = emptyMap()
}

private fun formOf(request: HttpRequestData): Parameters =
    (request.body as FormDataContent).formData

private fun malEntry(
    type: MediaType,
    status: MediaListStatus = MediaListStatus.CURRENT,
    isRewatching: Boolean = false,
    startedAt: MirrorDate? = null,
) = MirrorEntry(
    sourceProvider = ProviderId.AniList,
    aniListId = malOnlyMediaId(100, type),
    malId = 100,
    type = type,
    status = status,
    progress = 4,
    progressVolumes = if (type == MediaType.MANGA) 2 else null,
    score = 8.5f,
    scoreFormat = ScoreFormat.POINT_10_DECIMAL,
    repeat = 1,
    priority = 2,
    notes = "note",
    isPrivate = false,
    isRewatching = isRewatching,
    startedAt = startedAt,
    completedAt = null,
)

private fun aniListEntry(notes: String = "note") = MirrorEntry(
    sourceProvider = ProviderId.Mal,
    aniListId = 10,
    malId = 20,
    type = MediaType.ANIME,
    status = MediaListStatus.CURRENT,
    progress = 4,
    progressVolumes = null,
    score = 8f,
    scoreFormat = ScoreFormat.POINT_10,
    repeat = 2,
    priority = 2,
    notes = notes,
    isPrivate = false,
    isRewatching = false,
    startedAt = MirrorDate(2024, 5),
    completedAt = null,
)

private fun malMirrorRepository(onBody: (Parameters) -> Unit): MalMediaListEntryRepository =
    MalMediaListEntryRepository(
        bridge = EmptyBridge(),
        client = MalEntryClient(
            http = MalHttp(
                httpClient = HttpClientForTest {
                    onBody(formOf(it))
                    respond(
                        """{"status":"watching","score":0,"num_episodes_watched":4}""",
                        HttpStatusCode.OK,
                    )
                },
                clientId = "test",
                redirectUri = "michi://oauth/callback",
            ),
            bridge = EmptyBridge(),
            tokenProvider = {
                com.luum.michi.app.core.auth.domain.AuthToken(
                    accessToken = "token",
                    savedAtEpochSeconds = currentEpochSeconds(),
                )
            },
        ),
    )

class MirrorWriterTest {

    @Test
    fun malExactSaveWritesOnlySelectedFieldsAndCanClearThem() = runBlocking {
        var body: Parameters? = null
        val repository = MalMediaListEntryRepository(
            bridge = EmptyBridge(),
            client = MalEntryClient(
                http = MalHttp(
                    httpClient = HttpClientForTest {
                        body = formOf(it)
                        respond(
                            """{"status":"watching","score":0,"num_episodes_watched":4}""",
                            HttpStatusCode.OK,
                        )
                    },
                    clientId = "test",
                    redirectUri = "michi://oauth/callback",
                ),
                bridge = EmptyBridge(),
                tokenProvider = {
                    com.luum.michi.app.core.auth.domain.AuthToken(
                        accessToken = "token",
                        savedAtEpochSeconds = currentEpochSeconds(),
                    )
                },
            ),
        )
        val entry = malEntry(MediaType.ANIME, startedAt = null)
        val values = assertNotNull(MirrorTranslator.toMal(entry))
        val fields = setOf(
            MirrorField.STATUS,
            MirrorField.NOTES,
            MirrorField.STARTED_AT,
            MirrorField.PRIORITY,
        )

        val result = repository.saveMirror(entry, values, fields)

        assertTrue(result is NetworkResult.Success)
        val sent = assertNotNull(body)
        assertEquals("watching", sent["status"])
        assertEquals("false", sent["is_rewatching"])
        assertEquals("note", sent["comments"], "the selected note is written")
        assertEquals("", sent["start_date"], "the selected date is cleared")
        assertEquals("1", sent["priority"])
        assertNull(sent["score"], "score was not selected")
        assertNull(sent["num_watched_episodes"], "progress was not selected")
    }

    @Test
    fun malExactSaveClearsASelectedEmptyNote() = runBlocking {
        var body: Parameters? = null
        val repository = MalMediaListEntryRepository(
            bridge = EmptyBridge(),
            client = MalEntryClient(
                http = MalHttp(
                    httpClient = HttpClientForTest {
                        body = formOf(it)
                        respond(
                            """{"status":"watching","score":0,"num_episodes_watched":4}""",
                            HttpStatusCode.OK,
                        )
                    },
                    clientId = "test",
                    redirectUri = "michi://oauth/callback",
                ),
                bridge = EmptyBridge(),
                tokenProvider = {
                    com.luum.michi.app.core.auth.domain.AuthToken(
                        accessToken = "token",
                        savedAtEpochSeconds = currentEpochSeconds(),
                    )
                },
            ),
        )
        val entry = malEntry(MediaType.ANIME).copy(notes = "")
        val values = assertNotNull(MirrorTranslator.toMal(entry))

        repository.saveMirror(entry, values, setOf(MirrorField.NOTES))

        assertEquals("", assertNotNull(body)["comments"])
    }

    @Test
    fun aniListMirrorOmitsPrivateHiddenAndAdvancedFields() = runBlocking {
        val graphQL = FakeGraphQL(listOf(AniListJson.parseToJsonElement("""{"SaveMediaListEntry":{"id":1}}""")))
        val repository = MediaListEntryRepositoryImpl(graphQL, UnsupportedFavouriteRepository())
        val entry = MirrorEntry(
            sourceProvider = ProviderId.Mal,
            aniListId = 10,
            malId = 20,
            type = MediaType.ANIME,
            status = MediaListStatus.CURRENT,
            progress = 4,
            progressVolumes = null,
            score = 8f,
            scoreFormat = ScoreFormat.POINT_10,
            repeat = 2,
            priority = 2,
            notes = "note",
            isPrivate = true,
            isRewatching = true,
            startedAt = MirrorDate(2024, 5),
            completedAt = null,
        )
        val values = assertNotNull(
            MirrorTranslator.toAniList(entry, ScoreFormat.POINT_100),
        )

        val result = repository.saveMirror(
            entry = entry,
            values = values,
            fields = setOf(
                MirrorField.STATUS,
                MirrorField.SCORE,
                MirrorField.PROGRESS,
                MirrorField.NOTES,
                MirrorField.STARTED_AT,
            ),
        )

        assertTrue(result is NetworkResult.Success)
        val variables = assertNotNull(graphQL.requests.single().variables)
        assertEquals("REPEATING", variables["status"]?.jsonPrimitive?.content)
        assertEquals("80.0", variables["score"]?.jsonPrimitive?.content)
        assertEquals("4", variables["progress"]?.jsonPrimitive?.content)
        assertEquals("note", variables["notes"]?.jsonPrimitive?.content)
        val startedAt = assertNotNull(variables["startedAt"]).jsonObject
        assertEquals("2024", startedAt["year"]?.jsonPrimitive?.content)
        assertEquals("5", startedAt["month"]?.jsonPrimitive?.content)
        assertTrue(startedAt["day"] is JsonNull)
        assertFalse("private" in variables)
        assertFalse("hiddenFromStatusLists" in variables)
        assertFalse("advancedScores" in variables)
        assertEquals(1, graphQL.requests.size)
    }

    @Test
    fun malMirrorWithStatusOnlyWritesOnlyStatus() = runBlocking {
        var body: Parameters? = null
        val repository = malMirrorRepository { body = it }
        val entry = malEntry(MediaType.ANIME)
        val values = assertNotNull(MirrorTranslator.toMal(entry))

        val result = repository.saveMirror(entry, values, setOf(MirrorField.STATUS))

        assertTrue(result is NetworkResult.Success)
        val sent = assertNotNull(body)
        assertEquals("watching", sent["status"])
        assertNull(sent["comments"], "an untouched note is never copied")
        assertNull(sent["score"], "an untouched score is never copied")
        assertNull(sent["num_watched_episodes"], "an untouched progress is never copied")
        assertNull(sent["priority"])
        assertNull(sent["num_times_rewatched"])
        assertNull(sent["start_date"])
        assertNull(sent["finish_date"])
    }

    @Test
    fun aniListMirrorWithStatusOnlyWritesOnlyStatus() = runBlocking {
        val graphQL = FakeGraphQL(
            listOf(AniListJson.parseToJsonElement("""{"SaveMediaListEntry":{"id":1}}""")),
        )
        val repository = MediaListEntryRepositoryImpl(graphQL, UnsupportedFavouriteRepository())
        val entry = aniListEntry()
        val values = assertNotNull(MirrorTranslator.toAniList(entry, ScoreFormat.POINT_100))

        val result = repository.saveMirror(entry, values, setOf(MirrorField.STATUS))

        assertTrue(result is NetworkResult.Success)
        val variables = assertNotNull(graphQL.requests.single().variables)
        assertEquals(setOf("mediaId", "status"), variables.keys)
        assertEquals("CURRENT", variables["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun aniListMirrorClearsASelectedEmptyNote() = runBlocking {
        val graphQL = FakeGraphQL(
            listOf(AniListJson.parseToJsonElement("""{"SaveMediaListEntry":{"id":1}}""")),
        )
        val repository = MediaListEntryRepositoryImpl(graphQL, UnsupportedFavouriteRepository())
        val entry = aniListEntry(notes = "")
        val values = assertNotNull(MirrorTranslator.toAniList(entry, ScoreFormat.POINT_100))

        repository.saveMirror(entry, values, setOf(MirrorField.NOTES))

        val variables = assertNotNull(graphQL.requests.single().variables)
        assertEquals("", variables["notes"]?.jsonPrimitive?.content)
    }
}

private fun HttpClientForTest(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine(handler))
