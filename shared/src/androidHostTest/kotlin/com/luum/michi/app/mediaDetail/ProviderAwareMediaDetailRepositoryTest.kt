package com.luum.michi.app.mediaDetail

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.language.domain.EnglishStrings
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.model.Page
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaDetail.domain.media.MediaDetailRepository
import com.luum.michi.app.mediaDetail.domain.media.model.MediaCharacterEntry
import com.luum.michi.app.mediaDetail.domain.media.model.MediaDetail
import com.luum.michi.app.mediaDetail.domain.media.model.MediaDetailType
import com.luum.michi.app.mediaDetail.domain.media.model.MediaEntryEditorData
import com.luum.michi.app.mediaDetail.domain.media.model.MediaRecommendationEntry
import com.luum.michi.app.mediaDetail.domain.media.model.MediaStaffEntry
import com.luum.michi.app.mediaDetail.repository.mal.ProviderAwareMediaDetailRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class NoopBridge : MediaIdBridge {
    override suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>> = NetworkResult.Success(emptyMap())

    override suspend fun resolveMal(aniListId: Int): NetworkResult<MalRef?> =
        NetworkResult.Success(MalRef(malId = 123, type = MediaType.ANIME))

    override suspend fun rememberMalRef(aniListId: Int, ref: MalRef) = Unit

    override suspend fun cachedIdMap(type: MediaType): Map<Int, Int> = emptyMap()
}

private class FakeAniListDetail(
    private val detail: MediaDetail,
    private val editor: MediaEntryEditorData = editorData(detail),
) : MediaDetailRepository {
    override suspend fun loadDetail(
        mediaId: Int,
        strings: LanguageStrings,
    ): NetworkResult<MediaDetail> = NetworkResult.Success(detail)

    override suspend fun loadEditorData(mediaId: Int): NetworkResult<MediaEntryEditorData> =
        NetworkResult.Success(editor)

    override suspend fun loadCharactersPage(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaCharacterEntry>> = error("unused")

    override suspend fun loadStaffPage(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaStaffEntry>> = error("unused")

    override suspend fun loadRecommendations(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaRecommendationEntry>> = error("unused")
}

private fun detail(
    idMal: Int? = 123,
    viewerEntry: MediaListViewerEntry? = null,
): MediaDetail = MediaDetail(
    id = 100,
    type = MediaDetailType.ANIME,
    idMal = idMal,
    title = "Show",
    cover = null,
    bannerUrl = null,
    paletteHex = null,
    format = "TV",
    status = null,
    episodes = 12,
    chapters = null,
    volumes = null,
    duration = null,
    genres = emptyList(),
    studios = emptyList(),
    producers = emptyList(),
    source = null,
    season = null,
    startedLabel = null,
    endedLabel = null,
    averageScore = null,
    popularity = null,
    favourites = null,
    descriptionPlain = "",
    isAdult = false,
    isFavourite = false,
    viewerEntry = viewerEntry,
    relations = emptyList(),
    characters = Page(emptyList(), hasNextPage = false, currentPage = 1),
    staff = Page(emptyList(), hasNextPage = false, currentPage = 1),
    recommendations = Page(emptyList(), hasNextPage = false, currentPage = 1),
    titleRomaji = null,
    titleNative = null,
    synonyms = emptyList(),
    tags = emptyList(),
    externalLinks = emptyList(),
    trailer = null,
)

private fun editorData(detail: MediaDetail) = MediaEntryEditorData(
    title = detail.title,
    type = detail.type,
    idMal = detail.idMal,
    episodes = detail.episodes,
    chapters = detail.chapters,
    volumes = detail.volumes,
    isFavourite = false,
    viewerEntry = detail.viewerEntry,
)

private fun token() = AuthToken(
    accessToken = "token",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600,
)

private fun malStatus(
    startDate: String? = null,
    finishDate: String? = null,
): String = buildJsonObject {
    put(
        "my_list_status",
        buildJsonObject {
            put("status", "watching")
            put("score", 8)
            put("num_episodes_watched", 5)
            put("priority", 2)
            put("num_times_rewatched", 1)
            put("comments", "a note")
            startDate?.let { put("start_date", it) }
            finishDate?.let { put("finish_date", it) }
        },
    )
}.toString()

private class Harness(
    engine: MockEngine,
    detail: MediaDetail = detail(),
    editor: MediaEntryEditorData = editorData(detail),
    var unauthorized: AuthToken? = null,
) {
    var active = ProviderId.Mal
    private val client = MalEntryClient(
        http = MalHttp(
            httpClient = HttpClient(engine),
            clientId = "test-client",
            redirectUri = "michi://oauth/callback",
        ),
        bridge = NoopBridge(),
        tokenProvider = { token() },
        onUnauthorized = { unauthorized = it },
    )
    val repository = ProviderAwareMediaDetailRepository(
        activeProvider = { active },
        aniList = FakeAniListDetail(detail, editor),
        malEntries = client,
    )
}

class ProviderAwareMediaDetailRepositoryTest {

    @Test
    fun malReplacesTheViewerEntryInDetailAndEditor() = runBlocking {
        val harness = Harness(MockEngine { respond(malStatus(), HttpStatusCode.OK) })

        val detail = harness.repository.loadDetail(100, EnglishStrings)
        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaDetail>>(detail)
        val entry = detail.value.viewerEntry
        assertEquals(100, entry?.id)
        assertEquals(MediaListStatus.CURRENT, entry?.status)
        assertEquals(5, entry?.progress)
        assertEquals(8f, entry?.score)
        assertEquals("a note", entry?.notes)
        assertEquals(1, entry?.repeat)
        assertEquals(2, entry?.priority)

        assertIs<NetworkResult.Success<MediaEntryEditorData>>(editor)
        assertEquals(100, editor.value.viewerEntry?.id)
    }

    @Test
    fun aniListIsUntouchedAndNeverCallsMal() = runBlocking {
        val sentinel = MediaListViewerEntry(
            id = 999,
            status = MediaListStatus.PLANNING,
            progress = 1,
            progressVolumes = null,
            score = 0f,
            notes = "",
            repeat = 0,
            priority = 0,
            isPrivate = false,
            hiddenFromStatusLists = false,
            startedAtMillis = null,
            completedAtMillis = null,
        )
        val harness = Harness(
            engine = MockEngine { error("AniList must not call MAL") },
            detail = detail(viewerEntry = sentinel),
        )
        harness.active = ProviderId.AniList

        val result = harness.repository.loadDetail(100, EnglishStrings)

        assertIs<NetworkResult.Success<MediaDetail>>(result)
        assertEquals(sentinel, result.value.viewerEntry)
    }

    @Test
    fun aTitleWithoutMalKeepsTheDetailButRejectsTheEditor() = runBlocking {
        val harness = Harness(
            engine = MockEngine { error("no MAL request for an unmapped title") },
            detail = detail(idMal = null),
        )

        val detail = harness.repository.loadDetail(100, EnglishStrings)
        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaDetail>>(detail)
        assertNull(detail.value.viewerEntry)
        assertIs<NetworkResult.Failure>(editor)
        assertEquals(NetworkError.EntryNotOnProvider, editor.error)
    }

    @Test
    fun aMissingMalEntryOpensTheEditorAsNew() = runBlocking {
        val harness = Harness(
            MockEngine { respond("""{"id":123}""", HttpStatusCode.OK) },
        )

        val detail = harness.repository.loadDetail(100, EnglishStrings)
        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaDetail>>(detail)
        assertNull(detail.value.viewerEntry)
        assertIs<NetworkResult.Success<MediaEntryEditorData>>(editor)
        assertNull(editor.value.viewerEntry)
    }

    @Test
    fun aMalErrorDoesNotHideTheDetailButPropagatesInTheEditor() = runBlocking {
        val harness = Harness(
            MockEngine { respond("", HttpStatusCode.InternalServerError) },
        )

        val detail = harness.repository.loadDetail(100, EnglishStrings)
        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaDetail>>(detail)
        assertNull(detail.value.viewerEntry)
        assertIs<NetworkResult.Failure>(editor)
        assertTrue(editor.error is NetworkError.Http)
    }

    @Test
    fun aMal404IsEntryNotOnProvider() = runBlocking {
        val harness = Harness(
            MockEngine { respond("", HttpStatusCode.NotFound) },
        )

        val detail = harness.repository.loadDetail(100, EnglishStrings)
        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaDetail>>(detail)
        assertNull(detail.value.viewerEntry)
        assertIs<NetworkResult.Failure>(editor)
        assertEquals(NetworkError.EntryNotOnProvider, editor.error)
    }

    @Test
    fun aMal401CallsOnUnauthorized() = runBlocking {
        val harness = Harness(
            MockEngine { respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized) },
        )

        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Failure>(editor)
        assertEquals("token", harness.unauthorized?.accessToken)
    }

    @Test
    fun partialDatesAreNormalizedForTheEditor() = runBlocking {
        val harness = Harness(
            MockEngine { respond(malStatus(startDate = "2020", finishDate = "2020-05"), HttpStatusCode.OK) },
        )

        val editor = harness.repository.loadEditorData(100)

        assertIs<NetworkResult.Success<MediaEntryEditorData>>(editor)
        assertEquals(
            com.luum.michi.app.core.model.calendarPartsToMillis(
                com.luum.michi.app.core.model.CalendarDateParts(2020, 1, 1),
            ),
            editor.value.viewerEntry?.startedAtMillis,
        )
        assertEquals(
            com.luum.michi.app.core.model.calendarPartsToMillis(
                com.luum.michi.app.core.model.CalendarDateParts(2020, 5, 1),
            ),
            editor.value.viewerEntry?.completedAtMillis,
        )
    }
}
