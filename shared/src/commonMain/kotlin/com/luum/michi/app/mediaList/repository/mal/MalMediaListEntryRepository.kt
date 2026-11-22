package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.mal.domain.MalEntryUpdate
import com.luum.michi.app.core.mal.domain.toMalStatus
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.formatMalDate
import com.luum.michi.app.core.mal.repository.toViewerEntry
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.requireAniListId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import kotlin.math.roundToInt

/**
 * Writes the viewer's MAL list: the editor keeps AniList media ids, so every
 * write first resolves the MAL id through [MediaIdBridge]. The PATCH is
 * partial; unsupported AniList fields are simply never sent.
 */
internal class MalMediaListEntryRepository(
    private val bridge: MediaIdBridge,
    private val client: MalEntryClient,
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
    ): NetworkResult<MediaListViewerEntry> {
        val aniListId = mediaRef.requireAniListId()
        val ref = when (val resolved = bridge.resolveMal(aniListId)) {
            is NetworkResult.Failure -> return resolved
            is NetworkResult.Success -> resolved.value
                ?: return NetworkResult.Failure(NetworkError.EntryNotOnProvider)
        }
        val isManga = ref.type == MediaType.MANGA
        val malStatus = status.toMalStatus(isManga)
            ?: return NetworkResult.Failure(
                NetworkError.Http(400, "MyAnimeList does not support REPEATING"),
            )

        val update = MalEntryUpdate(
            status = malStatus,
            score = score.roundToInt().coerceIn(0, 10),
            progress = progress.coerceAtLeast(0),
            progressVolumes = if (isManga) progressVolumes?.coerceAtLeast(0) else null,
            priority = priority.coerceIn(0, 2),
            repeat = repeat.coerceAtLeast(0),
            comments = notes,
            // Empty means "clear"; the client omits a date the user did not touch.
            startDate = startedAtMillis?.let(::formatMalDate).orEmpty(),
            finishDate = completedAtMillis?.let(::formatMalDate).orEmpty(),
        )
        return client.save(aniListId, ref, update)
            .map { entry -> entry.toViewerEntry(aniListId, isManga) }
    }

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
    ): NetworkResult<Unit> {
        val aniListId = mediaRef.requireAniListId()
        val ref = when (val resolved = bridge.resolveMal(aniListId)) {
            is NetworkResult.Failure -> return resolved
            is NetworkResult.Success -> resolved.value
                ?: return NetworkResult.Failure(NetworkError.EntryNotOnProvider)
        }
        val isManga = ref.type == MediaType.MANGA
        val malStatus = if (status == null) {
            null
        } else {
            status.toMalStatus(isManga) ?: return NetworkResult.Failure(
                NetworkError.Http(400, "MyAnimeList does not support REPEATING"),
            )
        }

        if (progress == null && progressVolumes == null && malStatus == null) {
            return NetworkResult.Success(Unit)
        }
        val update = MalEntryUpdate(
            status = malStatus,
            progress = progress?.coerceAtLeast(0),
            progressVolumes = if (isManga) progressVolumes?.coerceAtLeast(0) else null,
        )
        return client.save(aniListId, ref, update).map { }
    }

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> = NetworkResult.Failure(
        NetworkError.Http(400, "MyAnimeList does not support favourites"),
    )

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> {
        val aniListId = mediaRef.requireAniListId()
        val ref: MalRef = when (val resolved = bridge.resolveMal(aniListId)) {
            is NetworkResult.Failure -> return resolved
            // The work is not on MAL, so there is nothing to delete.
            is NetworkResult.Success -> resolved.value ?: return NetworkResult.Success(Unit)
        }
        return client.delete(aniListId, ref)
    }
}
