package com.luum.michi.app.mediaList

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.favourite.repository.FavouriteRepositoryImpl
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.media.repository.dto.MediaTitleDto
import com.luum.michi.app.core.medialist.repository.MediaListEntryRepositoryImpl
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.CachedViewerStore
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.mediaList.domain.anime.model.AnimeListSection
import com.luum.michi.app.mediaList.repository.MediaListDiskCache
import com.luum.michi.app.mediaList.repository.MediaListEntryDto
import com.luum.michi.app.mediaList.repository.anime.AnimeListRepositoryImpl
import com.luum.michi.app.mediaList.repository.manga.MangaListRepositoryImpl
import com.luum.michi.app.mediaList.repository.listCacheFileName
import com.luum.michi.app.mediaList.ui.anime.state.AnimeListStateHolder
import com.luum.michi.app.mediaList.ui.manga.state.MangaListStateHolder
import com.luum.michi.app.testSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Helpers ────────────────────────────────────────────────────────────────

private val InlineCacheDispatcher = Dispatchers.Unconfined

private fun diskCache(store: InMemoryFileStore): MediaListDiskCache =
    MediaListDiskCache(store, decodeDispatcher = InlineCacheDispatcher)

private fun animeHolder(
    graphQL: FakeGraphQL,
    cache: MediaListDiskCache,
    scope: CoroutineScope,
    entryRepository: MediaListEntryRepository = MediaListEntryRepositoryImpl(graphQL, FavouriteRepositoryImpl(graphQL)),
): AnimeListStateHolder = AnimeListStateHolder(
    repository = AnimeListRepositoryImpl(graphQL, cache),
    entryRepository = entryRepository,
    scope = scope,
)

private fun mangaHolder(
    graphQL: FakeGraphQL,
    cache: MediaListDiskCache,
    scope: CoroutineScope,
    entryRepository: MediaListEntryRepository = MediaListEntryRepositoryImpl(graphQL, FavouriteRepositoryImpl(graphQL)),
): MangaListStateHolder = MangaListStateHolder(
    repository = MangaListRepositoryImpl(graphQL, cache),
    entryRepository = entryRepository,
    scope = scope,
)

private fun cachedAnimeDto(mediaId: Int, title: String): MediaListEntryDto = MediaListEntryDto(
    id = mediaId,
    status = "CURRENT",
    progress = 5,
    media = MediaListMediaDto(
        id = mediaId,
        format = "TV",
        status = "RELEASING",
        episodes = 12,
        title = MediaTitleDto(romaji = title),
    ),
)

private fun cachedMangaDto(mediaId: Int, chapters: Int, volumes: Int): MediaListEntryDto = MediaListEntryDto(
    id = mediaId,
    status = "CURRENT",
    progress = chapters,
    progressVolumes = volumes,
    media = MediaListMediaDto(
        id = mediaId,
        format = "MANGA",
        status = "RELEASING",
        chapters = 100,
        volumes = 10,
        title = MediaTitleDto(romaji = "Cached Manga"),
    ),
)

private fun completedMovieDto(mediaId: Int): MediaListEntryDto = MediaListEntryDto(
    id = mediaId,
    status = "COMPLETED",
    progress = 1,
    media = MediaListMediaDto(
        id = mediaId,
        format = "MOVIE",
        status = "FINISHED",
        episodes = 1,
        title = MediaTitleDto(romaji = "Movie"),
    ),
)

private fun networkCollection(mediaId: Int, title: String, progress: Int = 1) = singleGroupCollection(
    listEntryJson(
        id = mediaId,
        status = "CURRENT",
        progress = progress,
        media = animeMediaJson(id = mediaId, romaji = title, english = null),
    ),
)

private fun mangaNetworkCollection(mediaId: Int, chapters: Int) = singleGroupCollection(
    listEntryJson(
        id = mediaId,
        status = "CURRENT",
        progress = chapters,
        progressVolumes = 1,
        media = mangaMediaJson(id = mediaId, romaji = "Network", chapters = 100, volumes = 10),
    ),
)

/** Counts progress saves so a test can prove a disabled `+1` never enqueued one. */
private class CountingEntryRepository : MediaListEntryRepository {
    var progressCalls = 0
        private set

    override suspend fun saveEntry(
        mediaRef: EntityRef,
        status: MediaListStatus,
        progress: Int,
        progressVolumes: Int?,
        score: Float,
        advancedScores: List<Float>?,
        notes: String,
        repeat: Int,
        priority: Int,
        isPrivate: Boolean,
        hiddenFromStatusLists: Boolean,
        startedAtMillis: Long?,
        completedAtMillis: Long?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> = NetworkResult.Failure(NetworkError.Unknown(null))

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        progressCalls++
        return NetworkResult.Success(
            MediaListViewerEntry(
                id = mediaRef.id.toInt(),
                status = status,
                progress = progress ?: 0,
                progressVolumes = progressVolumes,
                score = 0f,
                notes = "",
                repeat = 0,
                priority = 0,
                isPrivate = false,
                hiddenFromStatusLists = false,
                startedAtMillis = null,
                completedAtMillis = null,
            ),
        )
    }

    override suspend fun toggleFavourite(mediaRef: EntityRef, isManga: Boolean): NetworkResult<Unit> =
        NetworkResult.Success(Unit)

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> =
        NetworkResult.Success(Unit)
}

private fun viewer() = Viewer(id = 1, name = "user")

private fun freshToken() = AuthToken(
    accessToken = "abc",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun expiredToken() = AuthToken(
    accessToken = "abc",
    savedAtEpochSeconds = 1L,
    expiresInSeconds = 1L,
)

private class FakeCachedViewerStore : CachedViewerStore {
    override fun load(): Viewer? = null
    override fun save(viewer: Viewer) {}
    override fun clear() {}
}

// ── Tests ──────────────────────────────────────────────────────────────────

class MediaListCacheTest {

    @Test
    fun coldStartShowsTheSavedListBeforeTheNetworkWithoutAFullSpinner() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "Cached"))) }

        val gate = CompletableDeferred<Unit>()
        val graphQL = FakeGraphQL(listOf(networkCollection(102, "Network"))).apply { this.gate = gate }
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined))

        holder.load(7)

        assertEquals(1, graphQL.calls, "the network revalidation was launched")
        assertEquals(listOf(101), holder.entries.map { it.id }, "the saved list is shown first")
        assertFalse(holder.isLoading, "hydrated entries turn the full-page spinner off")

        gate.complete(Unit)

        assertEquals(listOf(102), holder.entries.map { it.id }, "the network replaces the saved list")
    }

    @Test
    fun aFailedRevalidationKeepsTheSavedListAndSurfacesTheError() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "Cached"))) }

        val graphQL = FakeGraphQL(listOf(networkCollection(102, "Network")), failureAt = 0)
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined))

        holder.load(7)

        assertEquals(listOf(101), holder.entries.map { it.id })
        assertFalse(holder.isLoading)
        assertTrue(holder.error != null, "the failed revalidation surfaces the compact error")
    }

    @Test
    fun aCorruptFileIsIgnoredDeletedAndLoadedFromTheNetwork() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        store.put(listCacheFileName(7, MediaType.ANIME), "{ not json")

        val gate = CompletableDeferred<Unit>()
        val graphQL = FakeGraphQL(listOf(networkCollection(102, "Network"))).apply { this.gate = gate }
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined))

        holder.load(7)

        assertFalse(store.contains(listCacheFileName(7, MediaType.ANIME)), "the corrupt file is deleted")
        assertTrue(holder.entries.isEmpty())
        assertTrue(holder.isLoading, "nothing hydrated: the normal spinner is on")

        gate.complete(Unit)

        assertEquals(listOf(102), holder.entries.map { it.id })
    }

    @Test
    fun aSuccessfulLoadWritesTheListFile() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val graphQL = FakeGraphQL(listOf(networkCollection(101, "A")))
        val repository = AnimeListRepositoryImpl(graphQL, cache)

        runBlocking { repository.loadList(7, splitCompleted = true) }

        assertTrue(store.contains(listCacheFileName(7, MediaType.ANIME)), "the load is persisted")
    }

    @Test
    fun theListFileKeyIncludesTheProvider() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val otherProvider = ProviderId("other")
        runBlocking {
            cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "AniList")), ProviderId.AniList)
            cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(202, "Other")), otherProvider)
        }

        assertNotEquals(
            listCacheFileName(7, MediaType.ANIME, ProviderId.AniList),
            listCacheFileName(7, MediaType.ANIME, otherProvider),
        )
        runBlocking {
            assertEquals(
                listOf(101),
                cache.read(7, MediaType.ANIME, ProviderId.AniList)?.map { it.id },
            )
            assertEquals(
                listOf(202),
                cache.read(7, MediaType.ANIME, otherProvider)?.map { it.id },
            )
        }
    }

    @Test
    fun v1AndV2FilesAreIgnoredAndDeletedOnTheFirstV3Write() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val legacyName = "media_list_v1_anime_7.json"
        val v2Name = "media_list_v2_${ProviderId.AniList.value}_anime_7.json"
        store.put(legacyName, "[]")
        store.put(v2Name, "[]")

        assertNull(runBlocking { cache.read(7, MediaType.ANIME) }, "old shapes are never read")
        assertTrue(store.contains(legacyName), "a read does not delete it")
        assertTrue(store.contains(v2Name), "a read does not delete it")

        runBlocking { cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "A"))) }

        assertTrue(store.contains(listCacheFileName(7, MediaType.ANIME)), "v3 was written")
        assertFalse(store.contains(legacyName), "the orphan v1 file is removed")
        assertFalse(store.contains(v2Name), "the same provider's v2 file is removed")
    }

    @Test
    fun aMalV3WriteNeverDeletesAniListLegacyFiles() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val aniListV1 = "media_list_v1_anime_7.json"
        val aniListV2 = "media_list_v2_${ProviderId.AniList.value}_anime_7.json"
        val malV2 = "media_list_v2_${ProviderId.Mal.value}_anime_7.json"
        store.put(aniListV1, "[]")
        store.put(aniListV2, "[]")
        store.put(malV2, "[]")

        runBlocking {
            cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "A")), ProviderId.Mal)
        }

        assertTrue(store.contains(aniListV1), "MAL never touches AniList's v1 file")
        assertTrue(store.contains(aniListV2), "MAL never touches AniList's v2 file")
        assertFalse(store.contains(malV2), "MAL removes its own v2 file")
    }

    @Test
    fun theSplitSettingIsAppliedWhenReadingTheCache() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.ANIME, listOf(completedMovieDto(101))) }
        val repository = AnimeListRepositoryImpl(FakeGraphQL(emptyList()), cache)

        runBlocking {
            val split = repository.loadCachedList(7, splitCompleted = true)!!.single()
            val merged = repository.loadCachedList(7, splitCompleted = false)!!.single()
            assertEquals(AnimeListSection.COMPLETED_MOVIE, split.status)
            assertEquals(AnimeListSection.COMPLETED, merged.status)
        }
    }

    @Test
    fun anotherUsersCachedListIsNeverShown() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(1, MediaType.ANIME, listOf(cachedAnimeDto(101, "User A"))) }

        val gate = CompletableDeferred<Unit>()
        val graphQL = FakeGraphQL(listOf(networkCollection(202, "User B"))).apply { this.gate = gate }
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined))

        holder.load(2)

        assertTrue(holder.entries.isEmpty(), "A's cached entries are never shown for B")

        gate.complete(Unit)

        assertEquals(listOf(202), holder.entries.map { it.id })
    }

    @Test
    fun logoutDeletesTheListFiles() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val storage = InMemoryTokenStorage()

        runBlocking {
            storage.save(freshToken())
            cache.write(1, MediaType.ANIME, listOf(cachedAnimeDto(101, "A")))
            val manager = testSessionManager(
                storage = storage,
                provider = TestAuthProvider(viewerResult = NetworkResult.Success(viewer())),
                cachedViewerStore = FakeCachedViewerStore(),
                onClearUserData = { cache.clear() },
            )
            manager.bootstrap()
            manager.logout()
        }

        assertFalse(store.contains(listCacheFileName(1, MediaType.ANIME)))
        assertTrue(store.clearCount > 0)
    }

    @Test
    fun aMidSessionUnauthorizedDeletesTheListFiles() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val storage = InMemoryTokenStorage()
        val token = freshToken()

        runBlocking {
            storage.save(token)
            cache.write(1, MediaType.ANIME, listOf(cachedAnimeDto(101, "A")))
            val manager = testSessionManager(
                storage = storage,
                provider = TestAuthProvider(viewerResult = NetworkResult.Success(viewer())),
                cachedViewerStore = FakeCachedViewerStore(),
                onClearUserData = { cache.clear() },
            )
            manager.bootstrap()
            manager.onUnauthorized(token)
        }

        assertFalse(store.contains(listCacheFileName(1, MediaType.ANIME)))
    }

    @Test
    fun anUnauthorizedRefreshDeletesTheListFiles() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val storage = InMemoryTokenStorage()

        runBlocking {
            storage.save(freshToken())
            cache.write(1, MediaType.ANIME, listOf(cachedAnimeDto(101, "A")))
            val manager = testSessionManager(
                storage = storage,
                provider = TestAuthProvider(
                    viewerResult = NetworkResult.Failure(NetworkError.Unauthorized(null)),
                ),
                cachedViewerStore = FakeCachedViewerStore(),
                onClearUserData = { cache.clear() },
            )
            manager.bootstrap()
        }

        assertFalse(store.contains(listCacheFileName(1, MediaType.ANIME)))
    }

    @Test
    fun anExpiredTokenAtStartupDeletesTheListFiles() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        val storage = InMemoryTokenStorage()

        runBlocking {
            storage.save(expiredToken())
            cache.write(1, MediaType.ANIME, listOf(cachedAnimeDto(101, "A")))
            val manager = testSessionManager(
                storage = storage,
                provider = TestAuthProvider(viewerResult = NetworkResult.Success(viewer())),
                cachedViewerStore = FakeCachedViewerStore(),
                onClearUserData = { cache.clear() },
            )
            manager.bootstrap()
        }

        assertFalse(store.contains(listCacheFileName(1, MediaType.ANIME)))
    }

    @Test
    fun incrementsAreDisabledUntilTheNetworkConfirms() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "Cached"))) }

        val gate = CompletableDeferred<Unit>()
        val graphQL = FakeGraphQL(listOf(networkCollection(101, "Network", progress = 7))).apply { this.gate = gate }
        val entries = CountingEntryRepository()
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined), entries)

        holder.load(7)

        assertFalse(holder.canEditEntries, "hydrated entries are not editable yet")
        holder.incrementProgress(holder.entries.single())
        assertEquals(5, holder.entries.single().progress, "a +1 on hydrated data changes nothing")
        assertEquals(0, entries.progressCalls, "no save is enqueued")

        gate.complete(Unit)

        assertTrue(holder.canEditEntries, "the network confirmation enables editing")
        holder.incrementProgress(holder.entries.single())
        assertEquals(8, holder.entries.single().progress, "the +1 now applies over the server value")
        assertEquals(1, entries.progressCalls)
    }

    @Test
    fun aFailedRevalidationKeepsIncrementsDisabled() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.ANIME, listOf(cachedAnimeDto(101, "Cached"))) }

        val graphQL = FakeGraphQL(listOf(networkCollection(101, "Network", progress = 7)), failureAt = 0)
        val entries = CountingEntryRepository()
        val holder = animeHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined), entries)

        holder.load(7)

        assertFalse(holder.canEditEntries)
        holder.incrementProgress(holder.entries.single())
        assertEquals(5, holder.entries.single().progress)
        assertEquals(0, entries.progressCalls)
    }

    @Test
    fun mangaIncrementsWaitForTheNetworkToo() {
        val store = InMemoryFileStore()
        val cache = diskCache(store)
        runBlocking { cache.write(7, MediaType.MANGA, listOf(cachedMangaDto(201, chapters = 3, volumes = 1))) }

        val gate = CompletableDeferred<Unit>()
        val graphQL = FakeGraphQL(listOf(mangaNetworkCollection(201, chapters = 5))).apply { this.gate = gate }
        val entries = CountingEntryRepository()
        val holder = mangaHolder(graphQL, cache, CoroutineScope(Dispatchers.Unconfined), entries)

        holder.load(7)

        assertFalse(holder.canEditEntries)
        holder.incrementChapters(holder.entries.single())
        holder.incrementVolumes(holder.entries.single())
        assertEquals(3, holder.entries.single().chaptersProgress)
        assertEquals(1, holder.entries.single().volumesProgress)
        assertEquals(0, entries.progressCalls)

        gate.complete(Unit)

        assertTrue(holder.canEditEntries)
        holder.incrementChapters(holder.entries.single())
        assertEquals(6, holder.entries.single().chaptersProgress)
        assertEquals(1, entries.progressCalls)
    }
}
