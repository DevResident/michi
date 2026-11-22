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

/** The one seam between a saved primary entry and the copy pipeline. */
internal interface MirrorSink {
    suspend fun enqueue(
        mediaRef: EntityRef,
        mediaType: MediaType,
        result: MediaListViewerEntry,
        fields: Set<MirrorField>,
    )
}
