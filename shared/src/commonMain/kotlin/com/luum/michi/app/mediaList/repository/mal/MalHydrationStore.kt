package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CancellationException

internal fun malHydrationFileName(type: MediaType): String =
    "mal_hydration_v1_${type.name.lowercase()}.txt"

internal fun malUnmatchedFileName(type: MediaType): String =
    "mal_unmatched_v1_${type.name.lowercase()}.txt"

/**
 * Last full media hydration, per list type. The list cache always revalidates
 * the MAL state, but AniList media only needs a full refresh once a day; this
 * timestamp lets the loader reuse cached media in between. It also remembers
 * the MAL ids AniList has no media for, so incremental loads do not ask for
 * them again until the next full hydration.
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

    suspend fun unmatched(type: MediaType): Set<Int> = try {
        files.read(malUnmatchedFileName(type))
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        emptySet()
    }

    suspend fun saveUnmatched(type: MediaType, malIds: Set<Int>) {
        try {
            files.write(malUnmatchedFileName(type), malIds.sorted().joinToString(","))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Cache-only: a failed write only costs an extra AniList batch.
        }
    }
}
