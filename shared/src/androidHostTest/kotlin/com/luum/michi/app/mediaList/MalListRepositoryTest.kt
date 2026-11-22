package com.luum.michi.app.mediaList

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.media.repository.dto.MediaCoverImageDto
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.media.repository.dto.MediaTitleDto
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.mediaList.domain.anime.model.AnimeListSection
import com.luum.michi.app.mediaList.domain.manga.model.MangaListSection
import com.luum.michi.app.mediaList.repository.MediaListDiskCache
import com.luum.michi.app.mediaList.repository.listCacheFileName
import com.luum.michi.app.mediaList.repository.mal.MalAnimeListRepository
import com.luum.michi.app.mediaList.repository.mal.MalHydrationStore
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import com.luum.michi.app.mediaList.repository.mal.MalMangaListRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val InlineCacheDispatcher = Dispatchers.Unconfined

private data class MalEntryJson(
    val malId: Int,
    val status: String,
    val score: Double = 0.0,
    val episodes: Int = 0,
    val chapters: Int = 0,
    val volumes: Int = 0,
    val rewatching: Boolean = false,
    val rereading: Boolean = false,
    val updatedAt: String? = null,
    val startDate: String? = null,
    val finishDate: String? = null,
    val priority: Int = 0,
)

private fun malPage(entries: List<MalEntryJson>, next: String? = null): String = buildJsonObject {
    put(
        "data",
        buildJsonArray {
            entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("node", buildJsonObject { put("id", entry.malId) })
                        put(
                            "list_status",
                            buildJsonObject {
                                put("status", entry.status)
                                // MAL sends an integer score; the DTO decodes it as Double.
                                put("score", entry.score.toInt())
                                put("num_episodes_watched", entry.episodes)
                                put("num_chapters_read", entry.chapters)
                                put("num_volumes_read", entry.volumes)
                                put("is_rewatching", entry.rewatching)
                                put("is_rereading", entry.rereading)
                                entry.updatedAt?.let { put("updated_at", it) }
                                entry.startDate?.let { put("start_date", it) }
                                entry.finishDate?.let { put("finish_date", it) }
                                put("priority", entry.priority)
                            },
                        )
                    },
                )
            }
        },
    )
    next?.let { put("paging", buildJsonObject { put("next", it) }) }
}.toString()

private fun media(
    id: Int,
    idMal: Int,
    title: String,
    format: String = "TV",
    episodes: Int? = 12,
    chapters: Int? = null,
    volumes: Int? = null,
): MediaListMediaDto = MediaListMediaDto(
    id = id,
    idMal = idMal,
    format = format,
    status = "FINISHED",
    episodes = episodes,
    chapters = chapters,
    volumes = volumes,
    averageScore = 80,
    popularity = 1000,
    favourites = 10,
    trending = 5,
    genres = listOf("Action"),
    season = "FALL",
    seasonYear = 2020,
    startDate = FuzzyDateDto(year = 2020, month = 10, day = 1),
    title = MediaTitleDto(romaji = title),
    isAdult = false,
    coverImage = MediaCoverImageDto(large = "https://cdn/$id.jpg", color = "#123456"),
)

private fun bridgePage(vararg media: MediaListMediaDto): JsonElement = buildJsonObject {
    put(
        "Page",
        buildJsonObject {
            put(
                "media",
                buildJsonArray {
                    media.forEach { item ->
                        add(AniListJson.encodeToJsonElement(MediaListMediaDto.serializer(), item))
                    }
                },
            )
        },
    )
}

private fun token() = AuthToken(
    accessToken = "token",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600,
)

private class Stack(
    engine: MockEngine,
    bridgePages: List<JsonElement>,
    val store: InMemoryFileStore = InMemoryFileStore(),
    bridgeFailureAt: Int = -1,
    hydrationStore: MalHydrationStore? = null,
    now: () -> Long = { 1_000L },
    onUnmatched: (MediaType, List<Int>) -> Unit = { _, _ -> },
    onUnauthorized: suspend (AuthToken) -> Unit = {},
) {
    val graphQL = FakeGraphQL(bridgePages, failureAt = bridgeFailureAt)
    val cache = MediaListDiskCache(store, decodeDispatcher = InlineCacheDispatcher)
    val loader = MalListLoader(
        http = MalHttp(
            httpClient = HttpClient(engine),
            clientId = "test-client",
            redirectUri = "michi://oauth/callback",
        ),
        tokenProvider = { token() },
        bridge = MediaIdBridgeImpl(graphQL, store),
        cache = cache,
        hydrationStore = hydrationStore,
        now = now,
        onUnauthorized = onUnauthorized,
        onUnmatched = onUnmatched,
    )
}

class MalListRepositoryTest {

    @Test
    fun animePagesAreCombinedAndHydratedWithAniListMedia() = runBlocking {
        val next = "https://api.myanimelist.net/v2/users/@me/animelist" +
            "?offset=2&fields=list_status&limit=1000&nsfw=true"
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("Bearer token", request.headers["Authorization"])
            val fields = request.url.parameters["fields"].orEmpty()
            assertTrue(fields.startsWith("list_status"), "fields=$fields")
            assertEquals("true", request.url.parameters["nsfw"])
            when (request.url.parameters["offset"]) {
                null -> {
                    assertEquals("1000", request.url.parameters["limit"])
                    // MAL omits these subfields unless they are named.
                    for (sub in listOf("priority", "num_times_rewatched", "comments")) {
                        assertTrue(sub in fields, "the list must ask for $sub")
                    }
                    respond(
                        malPage(
                            entries = listOf(
                                MalEntryJson(
                                    malId = 101,
                                    status = "watching",
                                    score = 8.0,
                                    episodes = 5,
                                    rewatching = true,
                                    updatedAt = "2020-01-01T00:00:00+00:00",
                                    startDate = "2020-10",
                                    priority = 2,
                                ),
                                MalEntryJson(
                                    malId = 102,
                                    status = "completed",
                                    score = 9.0,
                                    episodes = 12,
                                    startDate = "2019",
                                    finishDate = "2020-01-05",
                                ),
                            ),
                            next = next,
                        ),
                        HttpStatusCode.OK,
                    )
                }
                "2" -> respond(
                    malPage(
                        entries = listOf(
                            MalEntryJson(
                                malId = 103,
                                status = "on_hold",
                                score = 7.0,
                                episodes = 3,
                                startDate = "2021-03-01",
                            ),
                        ),
                    ),
                    HttpStatusCode.OK,
                )
                else -> error("unexpected offset")
            }
        }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(
                bridgePage(
                    media(1001, 101, "Show 1"),
                    media(1002, 102, "Show 2"),
                    media(1003, 103, "Show 3"),
                ),
            ),
        )

        val result = MalAnimeListRepository(stack.loader).loadList(1, splitCompleted = true)

        assertTrue(result is NetworkResult.Success)
        assertEquals(2, requests)
        val entries = result.value
        assertEquals(3, entries.size)

        val first = entries[0]
        assertEquals(1001, first.id)
        assertEquals("Show 1", first.title)
        assertEquals(AnimeListSection.WATCHING, first.status)
        assertEquals(5, first.progress)
        assertEquals(12, first.totalEpisodes)
        assertEquals(8.0, first.score)
        assertEquals(2, first.priority)
        assertEquals(1_577_836_800L, first.updatedAt)
        assertEquals(20201000, first.startedAtInt)
        assertEquals(0, first.completedAtInt)
        assertFalse(first.isAdult)
        assertEquals(80, first.averageScore)

        assertEquals(AnimeListSection.COMPLETED_TV, entries[1].status)
        assertEquals(20200105, entries[1].completedAtInt)
        assertEquals(AnimeListSection.PAUSED, entries[2].status)

        assertNotNull(stack.cache.read(1, MediaType.ANIME, ProviderId.Mal))
        assertEquals(null, stack.cache.read(1, MediaType.ANIME, ProviderId.AniList))
        assertTrue(stack.store.contains(listCacheFileName(1, MediaType.ANIME, ProviderId.Mal)))

        val cached = MalAnimeListRepository(stack.loader).loadCachedList(1, splitCompleted = false)
        assertEquals(3, cached?.size)
        assertEquals(AnimeListSection.COMPLETED, cached?.get(1)?.status)
    }

    @Test
    fun mangaUsesChaptersAndVolumes() = runBlocking {
        val engine = MockEngine {
            respond(
                malPage(
                    entries = listOf(
                        MalEntryJson(
                            malId = 201,
                            status = "reading",
                            score = 7.0,
                            chapters = 10,
                            volumes = 2,
                            rereading = true,
                            startDate = "2020-01",
                        ),
                        MalEntryJson(
                            malId = 202,
                            status = "plan_to_read",
                        ),
                        MalEntryJson(
                            malId = 203,
                            status = "completed",
                            chapters = 100,
                            volumes = 10,
                            finishDate = "2021-12-31",
                        ),
                    ),
                ),
                HttpStatusCode.OK,
            )
        }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(
                bridgePage(
                    media(2001, 201, "Manga 1", format = "MANGA", episodes = null, chapters = 100, volumes = 10),
                    media(2002, 202, "Manga 2", format = "MANGA", episodes = null, chapters = 100, volumes = 10),
                    media(2003, 203, "Manga 3", format = "MANGA", episodes = null, chapters = 100, volumes = 10),
                ),
            ),
        )

        val result = MalMangaListRepository(stack.loader).loadList(1, splitCompleted = true)

        assertTrue(result is NetworkResult.Success)
        val entries = result.value
        assertEquals(3, entries.size)
        assertEquals(MangaListSection.CURRENT, entries[0].status)
        assertEquals(10, entries[0].chaptersProgress)
        assertEquals(2, entries[0].volumesProgress)
        assertEquals(MangaListSection.PLANNING, entries[1].status)
        assertEquals(MangaListSection.COMPLETED_MANGA, entries[2].status)
        assertEquals(20211231, entries[2].completedAtInt)
    }

    @Test
    fun unmatchedEntriesAreOmittedAndCounted() = runBlocking {
        val engine = MockEngine {
            respond(
                malPage(
                    entries = listOf(
                        MalEntryJson(malId = 101, status = "watching"),
                        MalEntryJson(malId = 102, status = "watching"),
                    ),
                ),
                HttpStatusCode.OK,
            )
        }
        var unmatched: Pair<MediaType, List<Int>>? = null
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(bridgePage(media(1001, 101, "Show 1"))),
            onUnmatched = { type, ids -> unmatched = type to ids },
        )

        val result = MalAnimeListRepository(stack.loader).loadList(1)

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, result.value.size)
        assertEquals(MediaType.ANIME to listOf(102), unmatched)
    }

    @Test
    fun aHydrationFailureFailsTheWholeLoadAndKeepsTheOldCache() = runBlocking {
        val store = InMemoryFileStore()
        store.put(listCacheFileName(1, MediaType.ANIME, ProviderId.Mal), "old-cache")
        val engine = MockEngine {
            respond(malPage(entries = listOf(MalEntryJson(malId = 101, status = "watching"))), HttpStatusCode.OK)
        }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(bridgePage()),
            store = store,
            bridgeFailureAt = 0,
        )

        val result = MalAnimeListRepository(stack.loader).loadList(1)

        assertIs<NetworkResult.Failure>(result)
        assertTrue(result.error is NetworkError.Http)
        assertEquals("old-cache", store.read(listCacheFileName(1, MediaType.ANIME, ProviderId.Mal)))
    }

    @Test
    fun aMalUnauthorizedCallsTheSessionCallback() = runBlocking {
        val engine = MockEngine { respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized) }
        var unauthorizedToken: String? = null
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(bridgePage()),
            onUnauthorized = { token -> unauthorizedToken = token.accessToken },
        )

        val result = MalAnimeListRepository(stack.loader).loadList(1)

        assertIs<NetworkResult.Failure>(result)
        assertEquals("token", unauthorizedToken)
    }

    @Test
    fun aForeignPagingHostStopsBeforeSendingTheToken() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                malPage(
                    entries = listOf(MalEntryJson(malId = 101, status = "watching")),
                    next = "https://evil.example/steal?offset=2",
                ),
                HttpStatusCode.OK,
            )
        }
        val stack = Stack(engine = engine, bridgePages = listOf(bridgePage()))

        val result = MalAnimeListRepository(stack.loader).loadList(1)

        assertIs<NetworkResult.Failure>(result)
        assertEquals(1, requests)
    }

    @Test
    fun aReloadOnlyHydratesNewEntries() = runBlocking {
        val store = InMemoryFileStore()
        val hydrationStore = MalHydrationStore(store)
        var now = 1_000L
        var entries = listOf(
            MalEntryJson(malId = 101, status = "watching"),
            MalEntryJson(malId = 102, status = "watching"),
        )
        val engine = MockEngine { respond(malPage(entries), HttpStatusCode.OK) }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(
                bridgePage(media(1001, 101, "Show 1"), media(1002, 102, "Show 2")),
                bridgePage(media(1003, 103, "Show 3")),
            ),
            store = store,
            hydrationStore = hydrationStore,
            now = { now },
        )
        val repository = MalAnimeListRepository(stack.loader)

        val first = repository.loadList(1)
        assertTrue(first is NetworkResult.Success)
        assertEquals(2, first.value.size)

        entries = entries + MalEntryJson(malId = 103, status = "watching")
        val second = repository.loadList(1)
        assertTrue(second is NetworkResult.Success)
        assertEquals(3, second.value.size)
        assertEquals(2, stack.graphQL.calls, "only the new id needs an AniList batch")
        assertEquals(
            listOf(103),
            (stack.graphQL.requests[1].variables?.get("ids") as JsonArray)
                .map { it.jsonPrimitive.content.toInt() },
        )
    }

    @Test
    fun aFullHydrationRunsAgainAfterTwentyFourHours() = runBlocking {
        val store = InMemoryFileStore()
        val hydrationStore = MalHydrationStore(store)
        var now = 1_000L
        val entries = listOf(
            MalEntryJson(malId = 101, status = "watching"),
            MalEntryJson(malId = 102, status = "watching"),
        )
        val engine = MockEngine { respond(malPage(entries), HttpStatusCode.OK) }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(
                bridgePage(media(1001, 101, "Show 1"), media(1002, 102, "Show 2")),
                bridgePage(media(1001, 101, "Show 1"), media(1002, 102, "Show 2")),
            ),
            store = store,
            hydrationStore = hydrationStore,
            now = { now },
        )
        val repository = MalAnimeListRepository(stack.loader)

        repository.loadList(1)
        now += 24L * 60L * 60L
        repository.loadList(1)

        assertEquals(
            listOf(101, 102),
            (stack.graphQL.requests[1].variables?.get("ids") as JsonArray)
                .map { it.jsonPrimitive.content.toInt() },
        )
    }

    @Test
    fun aForcedHydrationIgnoresTheCachedMedia() = runBlocking {
        val store = InMemoryFileStore()
        val hydrationStore = MalHydrationStore(store)
        val entries = listOf(MalEntryJson(malId = 101, status = "watching"))
        val engine = MockEngine { respond(malPage(entries), HttpStatusCode.OK) }
        val stack = Stack(
            engine = engine,
            bridgePages = listOf(
                bridgePage(media(1001, 101, "Show 1")),
                bridgePage(media(1001, 101, "Show 1")),
            ),
            store = store,
            hydrationStore = hydrationStore,
        )
        val repository = MalAnimeListRepository(stack.loader)

        repository.loadList(1)
        repository.loadList(1, forceHydration = true)

        assertEquals(
            listOf(101),
            (stack.graphQL.requests[1].variables?.get("ids") as JsonArray)
                .map { it.jsonPrimitive.content.toInt() },
        )
    }

    @Test
    fun aRepeatedNextUrlStopsThePagination() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            respond(
                malPage(
                    entries = listOf(MalEntryJson(malId = 101, status = "watching")),
                    next = request.url.toString(),
                ),
                HttpStatusCode.OK,
            )
        }
        val stack = Stack(engine = engine, bridgePages = listOf(bridgePage()))

        val result = MalAnimeListRepository(stack.loader).loadList(1)

        assertIs<NetworkResult.Failure>(result)
        assertEquals(1, requests)
    }
}
