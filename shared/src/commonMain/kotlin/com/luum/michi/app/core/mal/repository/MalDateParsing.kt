package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.model.CalendarDateParts
import com.luum.michi.app.core.model.calendarPartsToMillis
import com.luum.michi.app.core.model.millisToCalendarParts
import kotlin.time.Instant

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

/** Partial MAL dates become a full display date (year → Jan 1, year-month → day 1). */
internal fun FuzzyDateDto?.toEditorMillis(): Long? {
    val year = this?.year ?: return null
    val month = this.month ?: 1
    val day = this.day ?: 1
    return runCatching {
        calendarPartsToMillis(CalendarDateParts(year = year, month = month, day = day))
    }.getOrNull()
}

/** A full `YYYY-MM-DD` for the editor/PATCH; null when the partial date is invalid. */
internal fun normalizeMalDate(raw: String?): String? {
    val date = parseMalFuzzyDate(raw) ?: return null
    val year = date.year ?: return null
    val millis = runCatching {
        calendarPartsToMillis(
            CalendarDateParts(year = year, month = date.month ?: 1, day = date.day ?: 1),
        )
    }.getOrNull() ?: return null
    return formatMalDate(millis)
}

internal fun formatMalDate(millis: Long): String {
    val parts = millisToCalendarParts(millis)
    return buildString {
        append(parts.year.toString().padStart(4, '0'))
        append('-')
        append(parts.month.toString().padStart(2, '0'))
        append('-')
        append(parts.day.toString().padStart(2, '0'))
    }
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
