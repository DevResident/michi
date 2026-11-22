package com.luum.michi.app.core.medialist.domain

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import kotlin.math.abs

/** One list row plus the catalog data the preview needs. */
internal data class MirrorMediaSummary(
    val aniListId: Int,
    val malId: Int?,
    val type: MediaType,
    val title: String,
    val coverUrl: String? = null,
    val totalEpisodes: Int? = null,
    val totalChapters: Int? = null,
    val totalVolumes: Int? = null,
)

internal data class MirrorSnapshotEntry(
    val media: MirrorMediaSummary,
    val entry: MirrorEntry,
)

internal data class MirrorSnapshot(
    val provider: ProviderId,
    val entries: List<MirrorSnapshotEntry>,
)

/** Values shown in the before/after line, already in the target account's scale. */
internal data class MirrorPreviewValues(
    val status: MediaListStatus?,
    val progress: Int,
    val progressVolumes: Int?,
    val score: Float,
    val priority: Int,
    val repeat: Int,
    val notes: String,
    val startedAt: MirrorDate?,
    val completedAt: MirrorDate?,
)

internal data class MirrorPreviewRow(
    val key: String,
    val media: MirrorMediaSummary,
    /** The primary entry to enqueue; for an update only [changedFields] are written. */
    val primary: MirrorEntry,
    val before: MirrorPreviewValues?,
    val after: MirrorPreviewValues,
    val changedFields: Set<MirrorField>,
    val newerInLinked: Boolean,
)

internal data class MirrorLinkedOnlyRow(
    val media: MirrorMediaSummary,
    val values: MirrorPreviewValues,
)

internal data class MirrorDiff(
    val targetProvider: ProviderId,
    val targetScoreFormat: ScoreFormat,
    val additions: List<MirrorPreviewRow>,
    val updates: List<MirrorPreviewRow>,
    val linkedOnly: List<MirrorLinkedOnlyRow>,
    val noEquivalent: Int,
    val privateOmitted: Int,
)

/**
 * Pure list comparison. Fields are compared in the target scale; an empty
 * primary field never erases a filled linked value during sync. Deleting a
 * field remains an explicit editor action through the automatic mirror.
 */
internal object MirrorDiffer {

    fun diff(
        primary: MirrorSnapshot,
        linked: MirrorSnapshot,
        targetProvider: ProviderId,
        targetScoreFormat: ScoreFormat,
    ): MirrorDiff {
        require(primary.provider != targetProvider) { "Primary and target must differ" }
        val linkedByKey = linked.entries.mapNotNull { entry ->
            entry.key()?.let { it to entry }
        }.toMap()
        val matched = mutableSetOf<String>()
        val additions = mutableListOf<MirrorPreviewRow>()
        val updates = mutableListOf<MirrorPreviewRow>()
        var noEquivalent = 0
        var privateOmitted = 0

        primary.entries.forEach { source ->
            val key = source.key()
            if (key == null || (source.entry.aniListId <= 0 && targetProvider == ProviderId.AniList)) {
                noEquivalent += 1
                return@forEach
            }
            if (source.entry.isPrivate && primary.provider == ProviderId.AniList) {
                privateOmitted += 1
                matched += key
                return@forEach
            }
            val target = linkedByKey[key]
            if (target == null) {
                val after = targetValues(source.entry, targetProvider, targetScoreFormat) ?: run {
                    noEquivalent += 1
                    return@forEach
                }
                additions += MirrorPreviewRow(
                    key = key,
                    media = source.media,
                    primary = source.entry,
                    before = null,
                    after = after,
                    changedFields = AllMirrorFields,
                    newerInLinked = false,
                )
                return@forEach
            }

            matched += key
            val before = linkedValues(target.entry)
            val after = targetValues(source.entry, targetProvider, targetScoreFormat) ?: run {
                noEquivalent += 1
                return@forEach
            }
            val effectiveAfter = protectEmptyFields(after, before)
            val changed = changedFields(effectiveAfter, before)
            if (changed.isEmpty()) return@forEach
            updates += MirrorPreviewRow(
                key = key,
                media = source.media,
                primary = source.entry,
                before = before,
                after = effectiveAfter,
                changedFields = changed,
                newerInLinked = isNewerInLinked(source.entry, target.entry),
            )
        }

        val linkedOnly = linked.entries.mapNotNull { entry ->
            val key = entry.key()
            if (key != null && key in matched) return@mapNotNull null
            MirrorLinkedOnlyRow(entry.media, linkedValues(entry.entry))
        }

        return MirrorDiff(
            targetProvider = targetProvider,
            targetScoreFormat = targetScoreFormat,
            additions = additions,
            updates = updates,
            linkedOnly = linkedOnly,
            noEquivalent = noEquivalent,
            privateOmitted = privateOmitted,
        )
    }

    private fun MirrorSnapshotEntry.key(): String? {
        val malId = media.malId ?: return null
        return "${media.type.name}:$malId"
    }

    private fun linkedValues(entry: MirrorEntry): MirrorPreviewValues = MirrorPreviewValues(
        status = if (entry.isRewatching && entry.status != MediaListStatus.REPEATING) {
            MediaListStatus.REPEATING
        } else {
            entry.status
        },
        progress = entry.progress,
        progressVolumes = entry.progressVolumes,
        score = entry.score,
        priority = entry.priority,
        repeat = entry.repeat,
        notes = entry.notes,
        startedAt = entry.startedAt,
        completedAt = entry.completedAt,
    )

    private fun targetValues(
        entry: MirrorEntry,
        targetProvider: ProviderId,
        targetFormat: ScoreFormat,
    ): MirrorPreviewValues? = when (targetProvider) {
        ProviderId.Mal -> {
            val values = MirrorTranslator.toMal(entry) ?: return null
            val status = if (values.isRewatching) {
                MediaListStatus.REPEATING
            } else {
                malStatusToMediaListStatus(values.status)
            }
            MirrorPreviewValues(
                status = status,
                progress = values.progress,
                progressVolumes = values.progressVolumes,
                score = values.score.toFloat(),
                priority = values.priority,
                repeat = values.repeat,
                notes = values.comments,
                startedAt = values.startDate,
                completedAt = values.finishDate,
            )
        }
        else -> {
            val values = MirrorTranslator.toAniList(entry, targetFormat) ?: return null
            MirrorPreviewValues(
                status = values.status,
                progress = values.progress,
                progressVolumes = values.progressVolumes,
                score = values.score,
                priority = values.priority,
                repeat = values.repeat,
                notes = values.notes,
                startedAt = values.startedAt,
                completedAt = values.completedAt,
            )
        }
    }

    /**
     * The empty-primary rule: notes, dates, priority, score and repeat never
     * remove a filled linked value. The after values show the linked value for
     * those fields, so the row is not misleading.
     */
    private fun protectEmptyFields(
        after: MirrorPreviewValues,
        before: MirrorPreviewValues,
    ): MirrorPreviewValues = after.copy(
        notes = if (after.notes.isBlank() && before.notes.isNotBlank()) {
            before.notes
        } else {
            after.notes
        },
        startedAt = if (after.startedAt == null && before.startedAt != null) {
            before.startedAt
        } else {
            after.startedAt
        },
        completedAt = if (after.completedAt == null && before.completedAt != null) {
            before.completedAt
        } else {
            after.completedAt
        },
        priority = if (after.priority == 0 && before.priority != 0) {
            before.priority
        } else {
            after.priority
        },
        score = if (after.score <= 0f && before.score > 0f) {
            before.score
        } else {
            after.score
        },
        repeat = if (after.repeat == 0 && before.repeat != 0) {
            before.repeat
        } else {
            after.repeat
        },
    )

    private fun changedFields(
        after: MirrorPreviewValues,
        before: MirrorPreviewValues,
    ): Set<MirrorField> = buildSet {
        if (after.status != before.status) add(MirrorField.STATUS)
        if (after.progress != before.progress) add(MirrorField.PROGRESS)
        if (after.progressVolumes != before.progressVolumes) add(MirrorField.PROGRESS_VOLUMES)
        if (abs(after.score - before.score) > 0.01f) add(MirrorField.SCORE)
        if (after.notes != before.notes) add(MirrorField.NOTES)
        if (after.priority != before.priority) add(MirrorField.PRIORITY)
        if (after.repeat != before.repeat) add(MirrorField.REPEAT)
        if (after.startedAt != before.startedAt) add(MirrorField.STARTED_AT)
        if (after.completedAt != before.completedAt) add(MirrorField.COMPLETED_AT)
    }

    private fun isNewerInLinked(primary: MirrorEntry, linked: MirrorEntry): Boolean {
        val primaryAt = primary.updatedAt ?: return false
        val linkedAt = linked.updatedAt ?: return false
        return linkedAt > primaryAt
    }
}
