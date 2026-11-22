package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.AllMirrorFields
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorSink
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Wraps the session's entry repository. A successful primary save is returned
 * first; the mirror is a best-effort, durable enqueue that never changes that
 * result. Deletes and favourites are deliberately not mirrored.
 */
internal class MirroringMediaListEntryRepository(
    private val primary: MediaListEntryRepository,
    private val mirror: MirrorSink,
) : MediaListEntryRepository {

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
        val result = primary.saveEntry(
            mediaRef = mediaRef,
            status = status,
            progress = progress,
            progressVolumes = progressVolumes,
            score = score,
            advancedScores = advancedScores,
            notes = notes,
            repeat = repeat,
            priority = priority,
            isPrivate = isPrivate,
            hiddenFromStatusLists = hiddenFromStatusLists,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
            mediaType = mediaType,
        )
        if (result is NetworkResult.Success) {
            if (!result.value.isPrivate || mediaRef.provider == ProviderId.Mal) {
                enqueueBestEffort(mediaRef, mediaType, result.value, AllMirrorFields)
            }
        }
        return result
    }

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        val result = primary.saveProgress(
            mediaRef = mediaRef,
            progress = progress,
            status = status,
            progressVolumes = progressVolumes,
            mediaType = mediaType,
        )
        if (result is NetworkResult.Success) {
            val fields = buildSet {
                if (status != null) add(MirrorField.STATUS)
                if (progress != null) add(MirrorField.PROGRESS)
                if (progressVolumes != null) add(MirrorField.PROGRESS_VOLUMES)
            }
            if (!result.value.isPrivate || mediaRef.provider == ProviderId.Mal) {
                enqueueBestEffort(mediaRef, mediaType, result.value, fields)
            }
        }
        return result
    }

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> = primary.toggleFavourite(mediaRef, isManga)

    override suspend fun deleteEntry(
        entryId: Int,
        mediaRef: EntityRef,
    ): NetworkResult<Unit> = primary.deleteEntry(entryId, mediaRef)

    private suspend fun enqueueBestEffort(
        mediaRef: EntityRef,
        mediaType: MediaType,
        result: MediaListViewerEntry,
        fields: Set<MirrorField>,
    ) {
        if (fields.isEmpty()) return
        try {
            withContext(NonCancellable) {
                mirror.enqueue(mediaRef, mediaType, result, fields)
            }
        } catch (_: Throwable) {
            // The primary save already succeeded; the copy is best-effort.
        }
    }
}
