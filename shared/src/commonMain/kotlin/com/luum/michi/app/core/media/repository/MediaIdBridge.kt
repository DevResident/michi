package com.luum.michi.app.core.media.repository

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

/** The bridge's on-disk map: malId -> AniList id, per media type. */
internal fun idBridgeFileName(type: MediaType): String =
    "id_bridge_v1_${type.name.lowercase()}.json"

/**
 * AniList is the catalog, MAL is the list state: this bridge resolves MAL ids to
 * the AniList media the lists need. It also persists the id map so 11.4c can
 * write to MAL without asking AniList again; the map never stores media data.
 */
internal interface MediaIdBridge {
    suspend fun hydrateByMalIds(
        malIds: List<Int>,
        type: MediaType,
    ): NetworkResult<Map<Int, MediaListMediaDto>>
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
        val name = idBridgeFileName(type)
        val existing: Map<Int, Int> = try {
            files.read(name)
                ?.let { raw -> runCatching { json.decodeFromString(mapSerializer, raw) }.getOrNull() }
                .orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyMap()
        }
        val merged = existing + found.mapValues { it.value.id }
        val encoded = try {
            json.encodeToString(mapSerializer, merged)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        try {
            files.write(name, encoded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Cache-only.
        }
    }

    private companion object {
        const val BatchSize = 50
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
