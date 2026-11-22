package com.luum.michi.app.core.medialist.domain

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
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
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry>

    /**
     * Partial progress save: null fields are omitted from the mutation, so
     * the server leaves them intact. Novels ([tracksByVolume]-style entries)
     * send volumes only, with [progress] null. The returned entry is the full
     * resulting state, so the mirror can copy the right fields safely.
     */
    suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus? = null,
        progressVolumes: Int? = null,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry>

    suspend fun toggleFavourite(mediaRef: EntityRef, isManga: Boolean): NetworkResult<Unit>

    /**
     * Deletes one entry. AniList uses [entryId] (its MediaListEntry id); MAL
     * resolves [mediaRef] to its own media id because it has no entry id.
     */
    suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit>
}
