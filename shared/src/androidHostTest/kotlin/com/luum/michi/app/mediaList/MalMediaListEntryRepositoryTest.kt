package com.luum.michi.app.mediaList

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.malOnlyMediaId
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.model.CalendarDateParts
import com.luum.michi.app.core.model.calendarPartsToMillis
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mal.MalMediaListEntryRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBridge(
    var ref: MalRef? = MalRef(malId = 123, type = MediaType.ANIME),
) : MediaIdBridge {
    override suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>> = NetworkResult.Success(emptyMap())

    var resolveCalls = 0

    override suspend fun resolveMal(aniListId: Int): NetworkResult<MalRef?> {
        resolveCalls++
        return NetworkResult.Success(ref)
    }

    override suspend fun rememberMalRef(aniListId: Int, ref: MalRef) = Unit

    override suspend fun cachedIdMap(type: MediaType): Map<Int, Int> = emptyMap()
}

private fun token() = AuthToken(
    accessToken = "token",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600,
)

private fun statusJson(
    status: String = "watching",
    score: Int = 8,
    episodes: Int = 5,
    chapters: Int = 0,
    volumes: Int = 0,
    comments: String = "note",
    startDate: String? = null,
    finishDate: String? = null,
    priority: Int = 2,
    timesRewatched: Int = 1,
    timesReread: Int = 0,
): JsonElement = buildJsonObject {
    put("status", status)
    put("score", score)
    put("num_episodes_watched", episodes)
    put("num_chapters_read", chapters)
    put("num_volumes_read", volumes)
    put("is_rewatching", false)
    put("is_rereading", false)
    put("priority", priority)
    put("num_times_rewatched", timesRewatched)
    put("num_times_reread", timesReread)
    put("comments", comments)
    startDate?.let { put("start_date", it) }
    finishDate?.let { put("finish_date", it) }
}

private fun statusBody(
    status: String = "watching",
    score: Int = 8,
    episodes: Int = 5,
    chapters: Int = 0,
    volumes: Int = 0,
    comments: String = "note",
    startDate: String? = null,
    finishDate: String? = null,
    priority: Int = 2,
    timesRewatched: Int = 1,
    timesReread: Int = 0,
): String = buildJsonObject {
    put("my_list_status", statusJson(
        status = status,
        score = score,
        episodes = episodes,
        chapters = chapters,
        volumes = volumes,
        comments = comments,
        startDate = startDate,
        finishDate = finishDate,
        priority = priority,
        timesRewatched = timesRewatched,
        timesReread = timesReread,
    ))
}.toString()

private fun engine(handler: MockRequestHandler): MockEngine = MockEngine(handler)

private fun formOf(request: HttpRequestData): Parameters =
    (request.body as FormDataContent).formData

private fun dateMillis(year: Int, month: Int, day: Int): Long =
    calendarPartsToMillis(CalendarDateParts(year, month, day))

private class Harness(
    engine: MockEngine,
    val bridge: FakeBridge = FakeBridge(),
    var unauthorized: AuthToken? = null,
) {
    val http = MalHttp(
        httpClient = HttpClient(engine),
        clientId = "test-client",
        redirectUri = "michi://oauth/callback",
    )
    val client = MalEntryClient(
        http = http,
        bridge = bridge,
        tokenProvider = { token() },
        onUnauthorized = { unauthorized = it },
    )
    val repository = MalMediaListEntryRepository(bridge, client)
}

private suspend fun MalMediaListEntryRepository.save(
    status: MediaListStatus = MediaListStatus.CURRENT,
    progress: Int = 5,
    progressVolumes: Int? = null,
    score: Float = 8f,
    notes: String = "note",
    repeat: Int = 1,
    priority: Int = 2,
    startedAtMillis: Long? = null,
    completedAtMillis: Long? = null,
    ref: EntityRef = aniListRef(100),
    mediaType: MediaType = MediaType.ANIME,
): NetworkResult<com.luum.michi.app.core.medialist.domain.MediaListViewerEntry> = saveEntry(
    mediaRef = ref,
    status = status,
    progress = progress,
    progressVolumes = progressVolumes,
    score = score,
    advancedScores = listOf(1f, 2f),
    notes = notes,
    repeat = repeat,
    priority = priority,
    isPrivate = true,
    hiddenFromStatusLists = true,
    startedAtMillis = startedAtMillis,
    completedAtMillis = completedAtMillis,
    mediaType = mediaType,
    changedFields = null,
)

class MalMediaListEntryRepositoryTest {

    @Test
    fun saveEntrySendsOnlyMalFieldsAndMapsTheResponse() = runBlocking {
        var requestBody: Parameters? = null
        val harness = Harness(
            engine {
                assertEquals(HttpMethod.Patch, it.method)
                assertEquals(
                    "https://api.myanimelist.net/v2/anime/123/my_list_status",
                    it.url.toString(),
                )
                assertEquals("Bearer token", it.headers["Authorization"])
                requestBody = formOf(it)
                respond(statusJson(timesRewatched = 2).toString(), HttpStatusCode.OK)
            },
        )

        val result = harness.repository.save(
            status = MediaListStatus.CURRENT,
            progress = 5,
            score = 7.6f,
            notes = "note",
            repeat = 2,
            priority = 7,
            startedAtMillis = dateMillis(2020, 1, 1),
            completedAtMillis = dateMillis(2020, 2, 2),
        )

        assertTrue(result is NetworkResult.Success)
        assertEquals(100, result.value.id, "the entry id is the AniList id")
        assertEquals("note", result.value.notes)
        assertEquals(2, result.value.repeat)
        assertEquals(2, result.value.priority)
        val body = assertNotNull(requestBody)
        assertEquals("watching", body["status"])
        assertEquals("8", body["score"], "score is rounded to an integer")
        assertEquals("5", body["num_watched_episodes"])
        assertEquals("2", body["priority"])
        assertEquals("2", body["num_times_rewatched"])
        assertEquals("note", body["comments"])
        assertEquals("2020-01-01", body["start_date"])
        assertEquals("2020-02-02", body["finish_date"])
        assertNull(body["is_rewatching"])
        assertNull(body["private"])
        assertNull(body["hiddenFromStatusLists"])
        assertNull(body["num_times_reread"])
        assertNull(body["advancedScores"])
    }

    @Test
    fun mangaUsesChaptersVolumesAndItsOwnStatusNames() = runBlocking {
        var requestBody: Parameters? = null
        val harness = Harness(
            bridge = FakeBridge(MalRef(malId = 321, type = MediaType.MANGA)),
            engine = engine {
                requestBody = formOf(it)
                respond(
                    statusJson(status = "plan_to_read", chapters = 10, volumes = 2).toString(),
                    HttpStatusCode.OK,
                )
            },
        )

        val result = harness.repository.save(
            status = MediaListStatus.PLANNING,
            progress = 10,
            progressVolumes = 2,
            repeat = 1,
            ref = aniListRef(100),
        )

        assertTrue(result is NetworkResult.Success)
        val body = assertNotNull(requestBody)
        assertEquals("plan_to_read", body["status"])
        assertEquals("10", body["num_chapters_read"])
        assertEquals("2", body["num_volumes_read"])
        assertEquals("1", body["num_times_reread"])
        assertNull(body["num_watched_episodes"])
        assertNull(body["num_times_rewatched"])
    }

    @Test
    fun repeatingIsRejectedWithoutARequest() = runBlocking {
        var calls = 0
        val harness = Harness(engine { calls++; respond("", HttpStatusCode.OK) })

        val result = harness.repository.save(status = MediaListStatus.REPEATING)

        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun saveProgressIsPartial() = runBlocking {
        var requestBody: Parameters? = null
        val harness = Harness(
            engine {
                requestBody = formOf(it)
                respond(statusJson().toString(), HttpStatusCode.OK)
            },
        )

        val result = harness.repository.saveProgress(
            mediaRef = aniListRef(100),
            progress = 6,
            status = null,
            progressVolumes = null,
            mediaType = MediaType.ANIME,
        )

        assertTrue(result is NetworkResult.Success)
        val body = assertNotNull(requestBody)
        assertEquals("6", body["num_watched_episodes"])
        assertNull(body["status"])
        assertNull(body["comments"])
        assertNull(body["score"])
        assertNull(body["start_date"])
        assertNull(body["finish_date"])
        assertNull(body["priority"])
    }

    @Test
    fun deleteTreats404AsSuccess() = runBlocking {
        val harness = Harness(
            engine { respond("", HttpStatusCode.NotFound) },
        )

        val result = harness.repository.deleteEntry(1, aniListRef(100))

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun aWrite401CallsOnUnauthorized() = runBlocking {
        val harness = Harness(
            engine { respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized) },
        )

        val result = harness.repository.save()

        assertTrue(result is NetworkResult.Failure)
        assertEquals("token", harness.unauthorized?.accessToken)
    }

    @Test
    fun notOnMalFailedWritesReturnTheProviderError() = runBlocking {
        val harness = Harness(
            engine { error("must not call the network") },
            bridge = FakeBridge(ref = null),
        )

        val result = harness.repository.save()

        assertTrue(result is NetworkResult.Failure)
        assertEquals(NetworkError.EntryNotOnProvider, result.error)
    }

    @Test
    fun anUntouchedPartialDateIsNotSent() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    HttpMethod.Get -> respond(
                        statusBody(status = "watching", startDate = "2020", finishDate = "2020-05"),
                        HttpStatusCode.OK,
                    )
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(
                            statusJson(startDate = "2020", finishDate = "2020-05").toString(),
                            HttpStatusCode.OK,
                        )
                    }
                    else -> error("unexpected method")
                }
            },
        )
        val read = harness.client.read(100, MediaType.ANIME, 123)
        assertTrue(read is NetworkResult.Success)

        val result = harness.repository.save(
            status = MediaListStatus.CURRENT,
            startedAtMillis = dateMillis(2020, 1, 1),
            completedAtMillis = dateMillis(2020, 5, 1),
        )

        assertTrue(result is NetworkResult.Success)
        val body = assertNotNull(patchBody)
        assertNull(body["start_date"], "a year-only MAL date must not be rewritten")
        assertNull(body["finish_date"], "a year-month MAL date must not be rewritten")
    }

    @Test
    fun aClearedDateIsSentAsAnEmptyValue() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    HttpMethod.Get -> respond(
                        statusBody(status = "watching", startDate = "2020", finishDate = "2020-05"),
                        HttpStatusCode.OK,
                    )
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(statusJson(status = "watching").toString(), HttpStatusCode.OK)
                    }
                    else -> error("unexpected method")
                }
            },
        )
        harness.client.read(100, MediaType.ANIME, 123)

        val result = harness.repository.save(
            status = MediaListStatus.CURRENT,
            startedAtMillis = null,
            completedAtMillis = null,
        )

        assertTrue(result is NetworkResult.Success)
        val body = assertNotNull(patchBody)
        assertEquals("", body["start_date"])
        assertEquals("", body["finish_date"])
    }

    @Test
    fun theEntryReadNamesEveryListStatusSubfield() = runBlocking {
        var fields: String? = null
        val harness = Harness(
            engine {
                fields = it.url.parameters["fields"]
                respond(statusBody(), HttpStatusCode.OK)
            },
        )

        harness.client.read(100, MediaType.ANIME, 123)

        val asked = assertNotNull(fields)
        assertTrue(asked.startsWith("my_list_status{"), "fields=$asked")
        for (sub in listOf("comments", "priority", "num_times_rewatched", "start_date", "finish_date")) {
            assertTrue(sub in asked, "the read must ask for $sub")
        }
    }

    @Test
    fun untouchedCommentsPriorityAndRepeatAreNotSent() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    HttpMethod.Get -> respond(
                        statusBody(comments = "keep me", priority = 2, timesRewatched = 3),
                        HttpStatusCode.OK,
                    )
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(statusJson(status = "completed").toString(), HttpStatusCode.OK)
                    }
                    else -> error("unexpected method")
                }
            },
        )
        harness.client.read(100, MediaType.ANIME, 123)

        val result = harness.repository.save(
            status = MediaListStatus.COMPLETED,
            notes = "keep me",
            priority = 2,
            repeat = 3,
        )

        assertTrue(result is NetworkResult.Success)
        val body = assertNotNull(patchBody)
        assertEquals("completed", body["status"])
        assertNull(body["comments"])
        assertNull(body["priority"])
        assertNull(body["num_times_rewatched"])
    }

    @Test
    fun aReadWithoutTheSubfieldsCannotWipeThem() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    // A read that leaves the subfields out: the editor shows defaults.
                    HttpMethod.Get -> respond(
                        buildJsonObject {
                            put(
                                "my_list_status",
                                buildJsonObject {
                                    put("status", "watching")
                                    put("score", 8)
                                    put("num_episodes_watched", 5)
                                },
                            )
                        }.toString(),
                        HttpStatusCode.OK,
                    )
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(statusJson().toString(), HttpStatusCode.OK)
                    }
                    else -> error("unexpected method")
                }
            },
        )
        harness.client.read(100, MediaType.ANIME, 123)

        harness.repository.save(score = 9f, notes = "", priority = 0, repeat = 0)

        val body = assertNotNull(patchBody)
        assertEquals("9", body["score"])
        assertNull(body["comments"], "empty comments must not overwrite MAL")
        assertNull(body["priority"])
        assertNull(body["num_times_rewatched"])
    }

    @Test
    fun changedCommentsPriorityAndRepeatAreSent() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    HttpMethod.Get -> respond(
                        statusBody(comments = "old", priority = 2, timesRewatched = 3),
                        HttpStatusCode.OK,
                    )
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(statusJson().toString(), HttpStatusCode.OK)
                    }
                    else -> error("unexpected method")
                }
            },
        )
        harness.client.read(100, MediaType.ANIME, 123)

        harness.repository.save(notes = "", priority = 0, repeat = 0)

        val body = assertNotNull(patchBody)
        assertEquals("", body["comments"], "a deliberate clear is sent")
        assertEquals("0", body["priority"])
        assertEquals("0", body["num_times_rewatched"])
    }

    @Test
    fun withoutAReadEmptyDefaultsAreNotSent() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine {
                patchBody = formOf(it)
                respond(statusJson().toString(), HttpStatusCode.OK)
            },
        )

        harness.repository.save(notes = "", priority = 0, repeat = 0)

        val body = assertNotNull(patchBody)
        assertNull(body["comments"])
        assertNull(body["priority"])
        assertNull(body["num_times_rewatched"])
    }

    @Test
    fun clearForgetsTheLastRead() = runBlocking {
        var patchBody: Parameters? = null
        val harness = Harness(
            engine { request ->
                when (request.method) {
                    HttpMethod.Get -> respond(statusBody(comments = "old"), HttpStatusCode.OK)
                    HttpMethod.Patch -> {
                        patchBody = formOf(request)
                        respond(statusJson().toString(), HttpStatusCode.OK)
                    }
                    else -> error("unexpected method")
                }
            },
        )
        harness.client.read(100, MediaType.ANIME, 123)
        harness.client.clear()

        harness.repository.save(notes = "")

        assertNull(assertNotNull(patchBody)["comments"], "no read left, so nothing is cleared")
    }

    @Test
    fun toggleFavouriteIsUnsupported() = runBlocking {
        val harness = Harness(engine { error("must not call the network") })

        val result = harness.repository.toggleFavourite(aniListRef(100), isManga = false)

        assertTrue(result is NetworkResult.Failure)
    }

    @Test
    fun aMalOnlyEntryWritesStraightToItsMalRecordWithoutTheBridge() = runBlocking {
        val paths = mutableListOf<Pair<HttpMethod, String>>()
        val harness = Harness(
            engine = MockEngine { request ->
                paths += request.method to request.url.encodedPath
                if (request.method == HttpMethod.Delete) {
                    respond("", HttpStatusCode.OK)
                } else {
                    respond(statusBody(status = "reading", chapters = 4), HttpStatusCode.OK)
                }
            },
        )
        val manga = aniListRef(malOnlyMediaId(29317, MediaType.MANGA))

        val progress = harness.repository.saveProgress(
            mediaRef = manga,
            progress = 4,
            status = null,
            progressVolumes = null,
            mediaType = MediaType.MANGA,
        )
        val deleted = harness.repository.deleteEntry(entryId = 1, mediaRef = manga)

        assertTrue(progress is NetworkResult.Success)
        assertTrue(deleted is NetworkResult.Success)
        assertEquals(
            listOf(
                HttpMethod.Patch to "/v2/manga/29317/my_list_status",
                HttpMethod.Delete to "/v2/manga/29317/my_list_status",
            ),
            paths,
        )
        assertEquals(0, harness.bridge.resolveCalls, "the synthetic id names its MAL record")
    }
}
