package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.mal.domain.MalEntryStatus
import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry

/**
 * MAL list status → the neutral viewer entry. The id is the **AniList** media
 * id (MAL has no list-entry id); the editor opens that AniList title and writes
 * back through `resolveMal`.
 */
internal fun MalEntryStatus.toViewerEntry(aniListId: Int, isManga: Boolean): MediaListViewerEntry =
    MediaListViewerEntry(
        id = aniListId,
        status = malStatusToMediaListStatus(status),
        progress = progress,
        progressVolumes = progressVolumes,
        score = score.toFloat(),
        advancedScores = emptyMap(),
        notes = comments.orEmpty(),
        repeat = if (isManga) numTimesReread else numTimesRewatched,
        priority = priority ?: 0,
        isPrivate = false,
        hiddenFromStatusLists = false,
        startedAtMillis = parseMalFuzzyDate(startDate)?.toEditorMillis(),
        completedAtMillis = parseMalFuzzyDate(finishDate)?.toEditorMillis(),
        isRewatching = if (isManga) isRereading else isRewatching,
        startedAtDate = parseMalFuzzyDate(startDate)?.toMirrorDate(),
        completedAtDate = parseMalFuzzyDate(finishDate)?.toMirrorDate(),
    )

private fun com.luum.michi.app.core.media.repository.dto.FuzzyDateDto?.toMirrorDate(): com.luum.michi.app.core.medialist.domain.MirrorDate? {
    val year = this?.year ?: return null
    return com.luum.michi.app.core.medialist.domain.MirrorDate(
        year = year,
        month = month,
        day = day,
    )
}
