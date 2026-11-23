package com.luum.michi.app.core.medialist.repository

import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CancellationException

/**
 * Moves the mirror outbox out of the disposable cache exactly once. A durable
 * copy always wins (a later save may already be there); the legacy file is
 * deleted only after the durable one is known to exist.
 */
internal suspend fun migrateMirrorOutbox(
    legacyCache: FileStore,
    durable: FileStore,
) {
    val legacy = legacyCache.read(MirrorOutboxFileName) ?: return
    try {
        if (durable.read(MirrorOutboxFileName).isNullOrBlank()) {
            durable.write(MirrorOutboxFileName, legacy)
        }
        legacyCache.delete(MirrorOutboxFileName)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // Keep the legacy copy so the next start can retry the move.
    }
}

/** Logout/401: drop the durable queue and any pre-migration cache copy. */
internal suspend fun clearMirrorOutbox(
    outbox: MirrorOutbox,
    legacyCache: FileStore,
) {
    outbox.clear()
    legacyCache.delete(MirrorOutboxFileName)
}
