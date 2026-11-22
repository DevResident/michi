package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import kotlin.time.Instant

/** MAL statuses collapse onto AniList's vocabulary; rewatches are a flag, not a section. */
internal fun malListStatusToMediaListStatus(status: String?): MediaListStatus = when (status?.lowercase()) {
    "watching", "reading" -> MediaListStatus.CURRENT
    "completed" -> MediaListStatus.COMPLETED
    "on_hold" -> MediaListStatus.PAUSED
    "dropped" -> MediaListStatus.DROPPED
    "plan_to_watch", "plan_to_read" -> MediaListStatus.PLANNING
    // Unknown/missing status keeps the same fallback the AniList mapper uses.
    else -> MediaListStatus.CURRENT
}

/**
 * MAL dates are `YYYY`, `YYYY-MM` or `YYYY-MM-DD` (some examples omit leading
 * zeros). A year-only date becomes month/day 0, exactly like AniList's
 * `toComparableInt()` expects.
 */
internal fun parseMalFuzzyDate(raw: String?): FuzzyDateDto? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = value.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    return FuzzyDateDto(year = year, month = month, day = day)
}

private val DatePrefix = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(.*)$""")

/** MAL `updated_at` (ISO 8601 with offset) to epoch seconds; tolerant of non-padded days. */
internal fun parseMalUpdatedAt(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { Instant.parse(value).epochSeconds }.getOrNull()?.let { return it }

    val match = DatePrefix.find(value) ?: return null
    val groups = match.groupValues
    val normalized = buildString {
        append(groups[1])
        append('-')
        append(groups[2].padStart(2, '0'))
        append('-')
        append(groups[3].padStart(2, '0'))
        append(groups[4])
    }
    return runCatching { Instant.parse(normalized).epochSeconds }.getOrNull()
}
