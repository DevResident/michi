package com.luum.michi.app.mediaList.repository

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Bump when the persisted [MediaListEntryDto] shape or the file key changes: the
 * version travels in the file name, so an old file is simply never read again
 * (no migration).
 */
private const val ListCacheFormatVersion = 3

/** The one place the on-disk list file name is built (tests seed/corrupt with it). */
internal fun listCacheFileName(
    userId: Int,
    type: MediaType,
    provider: ProviderId = ProviderId.AniList,
): String =
    "media_list_v${ListCacheFormatVersion}_${provider.value}_${type.name.lowercase()}_$userId.json"

/** The v1 key had no provider segment; it is AniList-only and removed on the first v3 write. */
private fun legacyListCacheFileName(userId: Int, type: MediaType): String =
    "media_list_v1_${type.name.lowercase()}_$userId.json"

/** The v2 key already had the provider segment; the v3 write drops it for the same provider. */
private fun v2ListCacheFileName(userId: Int, type: MediaType, provider: ProviderId): String =
    "media_list_v2_${provider.value}_${type.name.lowercase()}_$userId.json"

/**
 * Disk cache for one user's anime/manga list: the flat [MediaListEntryDto] list
 * exactly as fetched. It is mapped to domain on read with the **current**
 * `splitCompleted`, so flipping that setting needs no separate file. A corrupt
 * file is deleted and read as absent, and a write failure is swallowed so it can
 * never turn an otherwise-successful network load into a failure. The file key
 * includes the provider and format version; v1/v2 files are ignored and removed
 * on the first v3 write.
 *
 * JSON decode/encode is CPU-heavy for a ~1 MB list, so it runs on
 * [decodeDispatcher] ([Dispatchers.Default] in the app; tests inject a
 * synchronous one). File IO itself is off the main thread inside [FileStore].
 */
internal class MediaListDiskCache(
    private val files: FileStore,
    private val json: Json = AniListJson,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val serializer = ListSerializer(MediaListEntryDto.serializer())

    suspend fun read(
        userId: Int,
        type: MediaType,
        provider: ProviderId = ProviderId.AniList,
    ): List<MediaListEntryDto>? {
        val name = listCacheFileName(userId, type, provider)
        val raw = files.read(name) ?: return null
        val decoded = withContext(decodeDispatcher) {
            try {
                json.decodeFromString(serializer, raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Corrupt / undecodable: treated as absent below.
                null
            }
        }
        if (decoded == null) {
            try {
                files.delete(name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed cleanup never breaks the caller: the file is ignored.
            }
        }
        return decoded
    }

    suspend fun write(
        userId: Int,
        type: MediaType,
        entries: List<MediaListEntryDto>,
        provider: ProviderId = ProviderId.AniList,
    ) {
        val name = listCacheFileName(userId, type, provider)
        val raw = withContext(decodeDispatcher) { json.encodeToString(serializer, entries) }
        try {
            files.write(name, raw)
            removeLegacyFile(userId, type, provider)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Cache-only: a full disk / missing permission must not fail the load.
        }
    }

    /**
     * The first v3 write drops that user's/type's v1/v2 files: v1 was
     * AniList-only with no provider segment, v2 is the same provider's previous
     * shape. A non-AniList provider must never delete an AniList v1 file.
     */
    private suspend fun removeLegacyFile(userId: Int, type: MediaType, provider: ProviderId) {
        val names = buildList {
            if (provider == ProviderId.AniList) add(legacyListCacheFileName(userId, type))
            add(v2ListCacheFileName(userId, type, provider))
        }
        names.forEach { name ->
            try {
                files.delete(name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed cleanup never breaks the caller.
            }
        }
    }

    /** Deletes only one account's cached lists (both media types + old shapes). */
    suspend fun clear(userId: Int, provider: ProviderId) {
        for (type in MediaType.entries) {
            val names = buildList {
                add(listCacheFileName(userId, type, provider))
                add(v2ListCacheFileName(userId, type, provider))
                if (provider == ProviderId.AniList) add(legacyListCacheFileName(userId, type))
            }
            names.forEach { name ->
                try {
                    files.delete(name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // A failed cleanup never breaks the logout/disconnect.
                }
            }
        }
    }

    suspend fun clear() {
        try {
            files.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best-effort privacy wipe.
        }
    }
}
