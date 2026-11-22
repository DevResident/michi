package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CancellationException

internal fun malHydrationFileName(type: MediaType): String =
    "mal_hydration_v1_${type.name.lowercase()}.txt"

/**
 * Last full media hydration, per list type. The list cache always revalidates
 * the MAL state, but AniList media only needs a full refresh once a day; this
 * timestamp lets the loader reuse cached media in between.
 */
internal class MalHydrationStore(
    private val files: FileStore,
) {
    suspend fun lastFull(type: MediaType): Long? = try {
        files.read(malHydrationFileName(type))?.trim()?.toLongOrNull()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    suspend fun markFull(type: MediaType, epochSeconds: Long) {
        try {
            files.write(malHydrationFileName(type), epochSeconds.toString())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Cache-only: a failed timestamp only forces an extra hydration later.
        }
    }
}
