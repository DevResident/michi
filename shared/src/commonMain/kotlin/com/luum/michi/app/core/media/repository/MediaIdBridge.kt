package com.luum.michi.app.core.media.repository

import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.media.repository.dto.MediaListMediaFields
import com.luum.michi.app.core.network.domain.AniListGraphQLClient
import com.luum.michi.app.core.network.domain.AniListGraphQLRequest
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MediaIdBridgeQuery = """
query MediaIdBridge(${'$'}ids: [Int], ${'$'}type: MediaType) {
  Page(perPage: 50) {
    media(idMal_in: ${'$'}ids, type: ${'$'}type) {
      idMal
      $MediaListMediaFields
    }
  }
}
"""

private const val MediaIdResolveQuery = """
query MediaIdResolve(${'$'}id: Int!) {
  Media(id: ${'$'}id) {
    idMal
    type
  }
}
"""

/** The bridge's on-disk map: malId -> AniList id, per media type. */
internal fun idBridgeFileName(type: MediaType): String =
    "id_bridge_v1_${type.name.lowercase()}.json"

/**
 * AniList is the catalog, MAL is the list state: this bridge resolves MAL ids to
 * the AniList media the lists need and back. It also persists the id map so
 * writes and incremental list reloads do not ask AniList again; the map never
 * stores media data.
 */
internal interface MediaIdBridge {
    suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>>

    /** Reverse lookup (map first, then AniList); null = the media is not on MAL. */
    suspend fun resolveMal(aniListId: Int): NetworkResult<MalRef?>

    /** Records a mapping learned from the detail/editor `idMal` field. */
    suspend fun rememberMalRef(aniListId: Int, ref: MalRef)

    /** Disk-only map for incremental list hydration, no network. */
    suspend fun cachedIdMap(type: MediaType): Map<Int, Int>
}

internal class MediaIdBridgeImpl(
    private val graphQLClient: AniListGraphQLClient,
    private val files: FileStore,
    private val json: Json = AniListJson,
) : MediaIdBridge {

    private val locks = mapOf(
        MediaType.ANIME to Mutex(),
        MediaType.MANGA to Mutex(),
    )
    private val mapSerializer = MapSerializer(Int.serializer(), Int.serializer())

    override suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>> {
        val requested = malIds.filter { it > 0 }.distinct()
        if (requested.isEmpty()) return NetworkResult.Success(emptyMap())

        return locks.getValue(type).withLock {
            val found = mutableMapOf<Int, MediaListMediaDto>()
            for (batch in requested.chunked(BatchSize)) {
                when (val result = fetch(batch, type)) {
                    is NetworkResult.Failure -> return@withLock result
                    is NetworkResult.Success -> result.value.forEach { media ->
                        val malId = media.idMal ?: return@forEach
                        val existing = found[malId]
                        // Two AniList entries can share an idMal; the lower id wins.
                        if (existing == null || media.id < existing.id) found[malId] = media
                    }
                }
            }

            persist(type, found)
            NetworkResult.Success(
                requested.mapNotNull { malId -> found[malId]?.let { malId to it } }.toMap(),
            )
        }
    }

    override suspend fun resolveMal(aniListId: Int): NetworkResult<MalRef?> {
        cachedRef(aniListId)?.let { return NetworkResult.Success(it) }

        val request = AniListGraphQLRequest(
            query = MediaIdResolveQuery,
            variables = JsonObject(mapOf("id" to JsonPrimitive(aniListId))),
            operationName = "MediaIdResolve",
        )
        return graphQLClient.execute(request) { dataJson ->
            AniListJson.decodeFromJsonElement(MediaIdResolveResponseDto.serializer(), dataJson)
                .media
                ?.let { media ->
                    val malId = media.idMal?.takeIf { it > 0 } ?: return@execute null
                    val type = when (media.type) {
                        "ANIME" -> MediaType.ANIME
                        "MANGA" -> MediaType.MANGA
                        else -> return@execute null
                    }
                    MalRef(malId = malId, type = type)
                }
        }.also { result ->
            if (result is NetworkResult.Success) {
                result.value?.let { ref -> rememberMalRef(aniListId, ref) }
            }
        }
    }

    override suspend fun rememberMalRef(aniListId: Int, ref: MalRef) {
        locks.getValue(ref.type).withLock {
            val map = readMap(ref.type)
            val existing = map[ref.malId]
            // Two AniList media can share an idMal; the lower id wins, as in
            // hydration, so the map does not flip between them.
            if (existing != null && existing <= aniListId) return@withLock
            writeMap(ref.type, map + (ref.malId to aniListId))
        }
    }

    override suspend fun cachedIdMap(type: MediaType): Map<Int, Int> =
        locks.getValue(type).withLock { readMap(type) }

    private suspend fun cachedRef(aniListId: Int): MalRef? {
        MediaType.entries.forEach { type ->
            val inverse = locks.getValue(type).withLock {
                readMap(type).entries.firstOrNull { it.value == aniListId }
            }
            if (inverse != null) return MalRef(malId = inverse.key, type = type)
        }
        return null
    }

    private suspend fun fetch(
        ids: List<Int>,
        type: MediaType,
    ): NetworkResult<List<MediaListMediaDto>> {
        val request = AniListGraphQLRequest(
            query = MediaIdBridgeQuery,
            variables = JsonObject(
                mapOf(
                    "ids" to JsonArray(ids.map { JsonPrimitive(it) }),
                    "type" to JsonPrimitive(type.name),
                ),
            ),
            operationName = "MediaIdBridge",
        )
        return graphQLClient.execute(request) { dataJson ->
            AniListJson
                .decodeFromJsonElement(MediaIdBridgeResponseDto.serializer(), dataJson)
                .page
                .media
        }
    }

    /** Best-effort: a cache write never turns a successful hydration into a failure. */
    private suspend fun persist(type: MediaType, found: Map<Int, MediaListMediaDto>) {
        if (found.isEmpty()) return
        writeMap(type, readMap(type) + found.mapValues { it.value.id })
    }

    private suspend fun readMap(type: MediaType): Map<Int, Int> = try {
        files.read(idBridgeFileName(type))
            ?.let { raw -> runCatching { json.decodeFromString(mapSerializer, raw) }.getOrNull() }
            .orEmpty()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        emptyMap()
    }

    private suspend fun writeMap(type: MediaType, map: Map<Int, Int>) {
        val encoded = try {
            json.encodeToString(mapSerializer, map)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        try {
            files.write(idBridgeFileName(type), encoded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Cache-only.
        }
    }

    private companion object {
        // Half the page size: several AniList media can share one idMal, and a
        // single page must still hold every match of the batch.
        const val BatchSize = 25
    }
}

@Serializable
private data class MediaIdBridgeResponseDto(
    @SerialName("Page") val page: MediaIdBridgePageDto,
)

@Serializable
private data class MediaIdBridgePageDto(
    val media: List<MediaListMediaDto> = emptyList(),
)

@Serializable
private data class MediaIdResolveResponseDto(
    @SerialName("Media") val media: MediaIdResolveDto? = null,
)

@Serializable
private data class MediaIdResolveDto(
    val idMal: Int? = null,
    val type: String? = null,
)
