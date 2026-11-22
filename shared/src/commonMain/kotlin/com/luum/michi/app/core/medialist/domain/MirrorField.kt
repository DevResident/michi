package com.luum.michi.app.core.medialist.domain

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType

/**
 * The fields an outbox operation is allowed to write. `STATUS` also carries the
 * provider's rewatch flag: a status is never copied without its companion flag.
 */
internal enum class MirrorField {
    STATUS,
    PROGRESS,
    PROGRESS_VOLUMES,
    SCORE,
    NOTES,
    PRIORITY,
    REPEAT,
    STARTED_AT,
    COMPLETED_AT,
}

internal val AllMirrorFields: Set<MirrorField> = MirrorField.entries.toSet()

/**
 * Fields whose empty/zero source value never clears the other account unless
 * the user changed them explicitly. Shared by the catch-up preview and the
 * automatic per-save copy so both apply the same rule.
 */
internal fun emptySensitiveFields(
    notes: String,
    priority: Int,
    repeat: Int,
    score: Float,
    hasStartedAt: Boolean,
    hasCompletedAt: Boolean,
): Set<MirrorField> = buildSet {
    if (notes.isBlank()) add(MirrorField.NOTES)
    if (priority == 0) add(MirrorField.PRIORITY)
    if (repeat == 0) add(MirrorField.REPEAT)
    if (score <= 0f) add(MirrorField.SCORE)
    if (!hasStartedAt) add(MirrorField.STARTED_AT)
    if (!hasCompletedAt) add(MirrorField.COMPLETED_AT)
}

/** The one seam between a saved primary entry and the copy pipeline. */
internal interface MirrorSink {
    suspend fun enqueue(
        mediaRef: EntityRef,
        mediaType: MediaType,
        result: MediaListViewerEntry,
        fields: Set<MirrorField>,
    )
}
