package com.luum.michi.app.core.medialist.domain

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.network.domain.NetworkResult

internal interface MediaListEntryRepository {
    suspend fun saveEntry(
        mediaRef: EntityRef,
        status: MediaListStatus,
        progress: Int,
        progressVolumes: Int?,
        score: Float,
        advancedScores: List<Float>? = null,
        notes: String,
        repeat: Int,
        priority: Int,
        isPrivate: Boolean,
        hiddenFromStatusLists: Boolean,
        startedAtMillis: Long?,
        completedAtMillis: Long?,
    ): NetworkResult<MediaListViewerEntry>

    /**
     * Partial progress save: null fields are omitted from the mutation, so
     * the server leaves them intact. Novels ([tracksByVolume]-style entries)
     * send volumes only, with [progress] null.
     */
    suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus? = null,
        progressVolumes: Int? = null,
    ): NetworkResult<Unit>

    suspend fun toggleFavourite(mediaRef: EntityRef, isManga: Boolean): NetworkResult<Unit>

    suspend fun deleteEntry(entryId: Int): NetworkResult<Unit>
}
