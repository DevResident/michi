package com.luum.michi.app.mirror.ui.model

import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.MirrorPreviewValues
import com.luum.michi.app.core.medialist.domain.label
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.formatScoreValue
import com.luum.michi.app.core.medialist.domain.scoreSuffixFor

/** One "before → after" line for the preview row. */
internal fun MirrorPreviewRow.summaryLine(
    strings: LanguageStrings,
    scoreFormat: ScoreFormat,
): String {
    val beforeValues = before
    if (beforeValues == null) {
        return "— → " + after.summary(strings, media, scoreFormat)
    }
    val parts = buildList {
        if (MirrorField.STATUS in changedFields) {
            add(statusText(beforeValues.status, strings, media) + " → " + statusText(after.status, strings, media))
        }
        if (MirrorField.PROGRESS in changedFields) {
            add(progressText(beforeValues, media) + " → " + progressText(after, media))
        }
        if (MirrorField.PROGRESS_VOLUMES in changedFields) {
            add(volumesText(beforeValues, media) + " → " + volumesText(after, media))
        }
        if (MirrorField.SCORE in changedFields) {
            add(scoreText(beforeValues.score, scoreFormat) + " → " + scoreText(after.score, scoreFormat))
        }
        if (MirrorField.PRIORITY in changedFields) {
            add(priorityText(beforeValues.priority) + " → " + priorityText(after.priority))
        }
        if (MirrorField.REPEAT in changedFields) {
            add(repeatText(beforeValues.repeat) + " → " + repeatText(after.repeat))
        }
        if (MirrorField.NOTES in changedFields) {
            add(noteText(beforeValues.notes) + " → " + noteText(after.notes))
        }
        if (MirrorField.STARTED_AT in changedFields) {
            add(dateText(beforeValues.startedAt) + " → " + dateText(after.startedAt))
        }
        if (MirrorField.COMPLETED_AT in changedFields) {
            add(dateText(beforeValues.completedAt) + " → " + dateText(after.completedAt))
        }
    }
    return parts.joinToString(" · ")
}

private fun MirrorPreviewValues.summary(
    strings: LanguageStrings,
    media: MirrorMediaSummary,
    scoreFormat: ScoreFormat,
): String = buildList {
    add(statusText(status, strings, media))
    add(progressText(this@summary, media))
    if (progressVolumes != null) add(volumesText(this@summary, media))
    if (score > 0f) add(scoreText(score, scoreFormat))
    if (priority > 0) add(priorityText(priority))
    if (repeat > 0) add(repeatText(repeat))
    if (notes.isNotBlank()) add(noteText(notes))
    startedAt?.let { add(dateText(it)) }
    completedAt?.let { add(dateText(it)) }
}.joinToString(" · ")

private fun statusText(
    status: MediaListStatus?,
    strings: LanguageStrings,
    media: MirrorMediaSummary,
): String = status?.label(strings, isManga = media.type == MediaType.MANGA) ?: "—"

private fun progressText(values: MirrorPreviewValues, media: MirrorMediaSummary): String {
    val total = if (media.type == MediaType.MANGA) media.totalChapters else media.totalEpisodes
    return if (total != null && total > 0) "${values.progress}/$total" else values.progress.toString()
}

private fun volumesText(values: MirrorPreviewValues, media: MirrorMediaSummary): String {
    if (media.type != MediaType.MANGA || values.progressVolumes == null) return "—"
    val total = media.totalVolumes
    return if (total != null && total > 0) {
        "${values.progressVolumes}/$total vol"
    } else {
        "${values.progressVolumes} vol"
    }
}

private fun scoreText(value: Float, format: ScoreFormat): String {
    if (value <= 0f) return "—"
    val formatted = formatScoreValue(format, value)
    return formatted + (scoreSuffixFor(format) ?: "")
}

private fun priorityText(value: Int): String = if (value == 0) "—" else value.toString()

private fun repeatText(value: Int): String = if (value == 0) "—" else "×$value"

private fun noteText(value: String): String {
    val clean = value.replace('\n', ' ').trim()
    if (clean.isEmpty()) return "—"
    val short = if (clean.length > 28) clean.take(28) + "…" else clean
    return "“$short”"
}

private fun dateText(value: MirrorDate?): String {
    val date = value ?: return "—"
    return buildString {
        append(date.year)
        date.month?.let {
            append('-')
            append(it.toString().padStart(2, '0'))
            date.day?.let { day ->
                append('-')
                append(day.toString().padStart(2, '0'))
            }
        }
    }
}
