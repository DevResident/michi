package com.luum.michi.app.mediaList.repository

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.network.domain.NetworkResult

/** Writes to the provider that owns the current session. */
internal class ProviderRoutingMediaListEntryRepository(
    private val activeProvider: () -> ProviderId,
    private val aniList: MediaListEntryRepository,
    private val mal: MediaListEntryRepository,
) : MediaListEntryRepository {

    private fun target(): MediaListEntryRepository =
        if (activeProvider() == ProviderId.Mal) mal else aniList

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
    ): NetworkResult<MediaListViewerEntry> = target().saveEntry(
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
    )

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
    ): NetworkResult<Unit> = target().saveProgress(mediaRef, progress, status, progressVolumes)

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> = target().toggleFavourite(mediaRef, isManga)

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> =
        target().deleteEntry(entryId, mediaRef)
}
