package com.luum.michi.app.mediaList

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.ProviderRoutingMediaListEntryRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private class RoutingCountingEntryRepository : MediaListEntryRepository {
    var saveCalls = 0
        private set
    var progressCalls = 0
        private set
    var favouriteCalls = 0
        private set
    var deleteCalls = 0
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
    ): NetworkResult<MediaListViewerEntry> {
        saveCalls++
        return NetworkResult.Success(entry())
    }

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        progressCalls++
        return NetworkResult.Success(entry())
    }

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> {
        favouriteCalls++
        return NetworkResult.Success(Unit)
    }

    override suspend fun deleteEntry(
        entryId: Int,
        mediaRef: EntityRef,
    ): NetworkResult<Unit> {
        deleteCalls++
        return NetworkResult.Success(Unit)
    }
}

private fun entry() = MediaListViewerEntry(
    id = 1,
    status = MediaListStatus.CURRENT,
    progress = 0,
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

class ProviderRoutingMediaListEntryRepositoryTest {

    @Test
    fun writesFollowTheActiveProvider() = runBlocking {
        var active = ProviderId.AniList
        val aniList = RoutingCountingEntryRepository()
        val mal = RoutingCountingEntryRepository()
        val router = ProviderRoutingMediaListEntryRepository(
            activeProvider = { active },
            aniList = aniList,
            mal = mal,
        )
        val ref = aniListRef(1)

        router.saveEntry(ref, MediaListStatus.CURRENT, 0, null, 0f, null, "", 0, 0, false, false, null, null, MediaType.ANIME)
        router.saveProgress(ref, 1, null, null, MediaType.ANIME)
        router.toggleFavourite(ref, isManga = false)
        router.deleteEntry(7, ref)
        assertEquals(1, aniList.saveCalls)
        assertEquals(1, aniList.progressCalls)
        assertEquals(1, aniList.favouriteCalls)
        assertEquals(1, aniList.deleteCalls)
        assertEquals(0, mal.saveCalls)

        active = ProviderId.Mal
        router.saveProgress(ref, 2, null, null, MediaType.ANIME)
        assertEquals(1, mal.progressCalls)
        assertEquals(1, aniList.progressCalls)
    }
}
