package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.mal.domain.MalEntryUpdate
import com.luum.michi.app.core.mal.domain.isMalOnlyMediaId
import com.luum.michi.app.core.mal.domain.malRefOfMalOnly
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
import com.luum.michi.app.core.medialist.domain.MalMirrorValues
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import kotlin.math.roundToInt

/**
 * Writes the viewer's MAL list: the editor keeps AniList media ids, so every
 * write first resolves the MAL id through [MediaIdBridge]. The PATCH is
 * partial; unsupported AniList fields are simply never sent. A MAL-only
 * synthetic id already names its MAL record, so it skips the bridge (and
 * AniList) entirely.
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
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        val aniListId = mediaRef.requireAniListId()
        val ref = when (val resolved = malRefFor(aniListId)) {
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
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        val aniListId = mediaRef.requireAniListId()
        val ref = when (val resolved = malRefFor(aniListId)) {
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
            return NetworkResult.Success(
                MediaListViewerEntry(
                    id = aniListId,
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
        val update = MalEntryUpdate(
            status = malStatus,
            progress = progress?.coerceAtLeast(0),
            progressVolumes = if (isManga) progressVolumes?.coerceAtLeast(0) else null,
        )
        return client.save(aniListId, ref, update)
            .map { entry -> entry.toViewerEntry(aniListId, isManga) }
    }

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> = NetworkResult.Failure(
        NetworkError.Http(400, "MyAnimeList does not support favourites"),
    )

    /**
     * Mirror-only PATCH: the selected fields are copied verbatim, including an
     * empty date/comments/priority (a deliberate clear). The normal editor save
     * keeps its omit-untouched behaviour.
     */
    suspend fun saveMirror(
        entry: MirrorEntry,
        values: MalMirrorValues,
        fields: Set<MirrorField>,
    ): NetworkResult<Unit> {
        if (fields.isEmpty()) return NetworkResult.Success(Unit)
        val ref = when (val resolved = malRefFor(entry.aniListId)) {
            is NetworkResult.Failure -> return resolved
            is NetworkResult.Success -> resolved.value
                ?: return NetworkResult.Failure(NetworkError.EntryNotOnProvider)
        }
        val isManga = ref.type == MediaType.MANGA
        val update = MalEntryUpdate(
            status = if (MirrorField.STATUS in fields) values.status else null,
            score = if (MirrorField.SCORE in fields) values.score else null,
            progress = if (MirrorField.PROGRESS in fields) values.progress else null,
            progressVolumes = if (MirrorField.PROGRESS_VOLUMES in fields && isManga) {
                values.progressVolumes ?: 0
            } else {
                null
            },
            priority = if (MirrorField.PRIORITY in fields) values.priority else null,
            repeat = if (MirrorField.REPEAT in fields) values.repeat else null,
            comments = if (MirrorField.NOTES in fields) values.comments else null,
            startDate = if (MirrorField.STARTED_AT in fields) {
                values.startDate?.let(::formatMirrorDate).orEmpty()
            } else {
                null
            },
            finishDate = if (MirrorField.COMPLETED_AT in fields) {
                values.finishDate?.let(::formatMirrorDate).orEmpty()
            } else {
                null
            },
            isRewatching = if (MirrorField.STATUS in fields) values.isRewatching else null,
        )
        return client.saveExact(entry.aniListId, ref, update).map { }
    }

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> {
        val aniListId = mediaRef.requireAniListId()
        val ref: MalRef = when (val resolved = malRefFor(aniListId)) {
            is NetworkResult.Failure -> return resolved
            // The work is not on MAL, so there is nothing to delete.
            is NetworkResult.Success -> resolved.value ?: return NetworkResult.Success(Unit)
        }
        return client.delete(aniListId, ref)
    }

    /** A MAL-only synthetic id names its MAL record itself; anything else goes through the bridge. */
    private suspend fun malRefFor(listId: Int): NetworkResult<MalRef?> =
        if (isMalOnlyMediaId(listId)) {
            NetworkResult.Success(malRefOfMalOnly(listId))
        } else {
            bridge.resolveMal(listId)
        }
}

/** MAL's partial date string: YYYY, YYYY-MM or YYYY-MM-DD. */
private fun formatMirrorDate(date: MirrorDate): String = buildString {
    append(date.year.toString().padStart(4, '0'))
    val month = date.month
    if (month != null) {
        append('-')
        append(month.toString().padStart(2, '0'))
        date.day?.let {
            append('-')
            append(it.toString().padStart(2, '0'))
        }
    }
}
