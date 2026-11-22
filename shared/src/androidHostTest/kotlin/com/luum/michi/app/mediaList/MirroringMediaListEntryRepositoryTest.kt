package com.luum.michi.app.mediaList

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.medialist.domain.AllMirrorFields
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorSink
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirroringMediaListEntryRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun savedEntry(
    id: Int = 7,
    isPrivate: Boolean = false,
    progress: Int = 3,
) = MediaListViewerEntry(
    id = id,
    status = MediaListStatus.CURRENT,
    progress = progress,
    progressVolumes = null,
    score = 8.5f,
    notes = "note",
    repeat = 1,
    priority = 2,
    isPrivate = isPrivate,
    hiddenFromStatusLists = false,
    startedAtMillis = null,
    completedAtMillis = null,
)

private class RecordingPrimary(
    var entry: MediaListViewerEntry = savedEntry(),
    var fail: NetworkError? = null,
) : MediaListEntryRepository {
    var saveCalls = 0
        private set
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
    ): NetworkResult<MediaListViewerEntry> {
        saveCalls++
        return fail?.let { NetworkResult.Failure(it) } ?: NetworkResult.Success(entry)
    }

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        progressCalls++
        return fail?.let { NetworkResult.Failure(it) } ?: NetworkResult.Success(entry)
    }

    override suspend fun toggleFavourite(mediaRef: EntityRef, isManga: Boolean): NetworkResult<Unit> =
        NetworkResult.Success(Unit)

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> =
        NetworkResult.Success(Unit)
}

private class RecordingSink(
    private val fail: Boolean = false,
) : MirrorSink {
    val calls = mutableListOf<Call>()

    data class Call(
        val id: Int,
        val type: MediaType,
        val fields: Set<MirrorField>,
        val result: MediaListViewerEntry,
    )

    override suspend fun enqueue(
        mediaRef: EntityRef,
        mediaType: MediaType,
        result: MediaListViewerEntry,
        fields: Set<MirrorField>,
    ) {
        if (fail) error("mirror down")
        calls += Call(mediaRef.id.toInt(), mediaType, fields, result)
    }
}

class MirroringMediaListEntryRepositoryTest {

    @Test
    fun saveEntryEnqueuesEveryFieldAndReturnsThePrimaryResult() = runBlocking {
        val primary = RecordingPrimary()
        val sink = RecordingSink()
        val repository = MirroringMediaListEntryRepository(primary, sink)

        val result = repository.saveEntry(
            mediaRef = aniListRef(7),
            status = MediaListStatus.CURRENT,
            progress = 3,
            progressVolumes = null,
            score = 8.5f,
            advancedScores = listOf(1f),
            notes = "note",
            repeat = 1,
            priority = 2,
            isPrivate = false,
            hiddenFromStatusLists = false,
            startedAtMillis = null,
            completedAtMillis = null,
            mediaType = MediaType.ANIME,
        )

        assertEquals(primary.entry, (result as NetworkResult.Success).value)
        assertEquals(1, sink.calls.size)
        assertEquals(AllMirrorFields, sink.calls.single().fields)
        assertEquals(MediaType.ANIME, sink.calls.single().type)
    }

    @Test
    fun saveProgressEnqueuesOnlyStatusAndProgress() = runBlocking {
        val primary = RecordingPrimary()
        val sink = RecordingSink()
        val repository = MirroringMediaListEntryRepository(primary, sink)

        repository.saveProgress(
            mediaRef = aniListRef(7),
            progress = 4,
            status = MediaListStatus.CURRENT,
            progressVolumes = null,
            mediaType = MediaType.ANIME,
        )

        assertEquals(
            setOf(MirrorField.STATUS, MirrorField.PROGRESS),
            sink.calls.single().fields,
        )
    }

    @Test
    fun saveProgressForMangaAlsoCarriesVolumes() = runBlocking {
        val primary = RecordingPrimary()
        val sink = RecordingSink()
        val repository = MirroringMediaListEntryRepository(primary, sink)

        repository.saveProgress(
            mediaRef = aniListRef(7),
            progress = 4,
            status = MediaListStatus.CURRENT,
            progressVolumes = 2,
            mediaType = MediaType.MANGA,
        )

        assertEquals(
            setOf(MirrorField.STATUS, MirrorField.PROGRESS, MirrorField.PROGRESS_VOLUMES),
            sink.calls.single().fields,
        )
    }

    @Test
    fun aPrivateEntryNeverReachesTheSink() = runBlocking {
        val primary = RecordingPrimary(entry = savedEntry(isPrivate = true))
        val sink = RecordingSink()
        val repository = MirroringMediaListEntryRepository(primary, sink)

        repository.saveEntry(
            mediaRef = aniListRef(7),
            status = MediaListStatus.CURRENT,
            progress = 3,
            progressVolumes = null,
            score = 8.5f,
            advancedScores = null,
            notes = "note",
            repeat = 1,
            priority = 2,
            isPrivate = true,
            hiddenFromStatusLists = false,
            startedAtMillis = null,
            completedAtMillis = null,
            mediaType = MediaType.ANIME,
        )

        assertTrue(sink.calls.isEmpty())

        repository.saveProgress(
            mediaRef = aniListRef(7),
            progress = 4,
            status = MediaListStatus.CURRENT,
            progressVolumes = null,
            mediaType = MediaType.ANIME,
        )
        assertTrue(sink.calls.isEmpty(), "a private progress save never reaches the sink either")
    }

    @Test
    fun aMirrorFailureNeverChangesThePrimaryResult() = runBlocking {
        val primary = RecordingPrimary()
        val repository = MirroringMediaListEntryRepository(primary, RecordingSink(fail = true))

        val result = repository.saveEntry(
            mediaRef = aniListRef(7),
            status = MediaListStatus.CURRENT,
            progress = 3,
            progressVolumes = null,
            score = 8.5f,
            advancedScores = null,
            notes = "note",
            repeat = 1,
            priority = 2,
            isPrivate = false,
            hiddenFromStatusLists = false,
            startedAtMillis = null,
            completedAtMillis = null,
            mediaType = MediaType.ANIME,
        )

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun deleteAndFavouriteNeverEnqueue() = runBlocking {
        val sink = RecordingSink()
        val repository = MirroringMediaListEntryRepository(RecordingPrimary(), sink)

        repository.deleteEntry(7, aniListRef(7))
        repository.toggleFavourite(aniListRef(7), isManga = false)

        assertTrue(sink.calls.isEmpty())
    }
}
