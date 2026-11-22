package com.luum.michi.app.core

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.media.repository.idBridgeFileName
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.mediaList.InMemoryFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun media(id: Int, idMal: Int?): MediaListMediaDto =
    MediaListMediaDto(id = id, idMal = idMal)

private fun page(vararg media: MediaListMediaDto): JsonElement = buildJsonObject {
    put(
        "Page",
        buildJsonObject {
            put(
                "media",
                buildJsonArray {
                    media.forEach { item ->
                        add(AniListJson.encodeToJsonElement(MediaListMediaDto.serializer(), item))
                    }
                },
            )
        },
    )
}

private fun askedIds(request: com.luum.michi.app.core.network.domain.AniListGraphQLRequest): List<Int> =
    (request.variables?.get("ids") as JsonArray).map { it.jsonPrimitive.content.toInt() }

class MediaIdBridgeTest {

    private fun bridge(
        graphQL: FakeGraphQL,
        store: InMemoryFileStore = InMemoryFileStore(),
    ): MediaIdBridgeImpl = MediaIdBridgeImpl(graphQL, store)

    @Test
    fun batchesIntoFifties() = runBlocking {
        val graphQL = FakeGraphQL(
            pages = listOf(page(), page(), page()),
        )
        val ids = (1..120).toList()

        val result = bridge(graphQL).hydrateByMalIds(ids, MediaType.ANIME)

        assertTrue(result is NetworkResult.Success)
        assertEquals(3, graphQL.calls)
        assertEquals(listOf(50, 50, 20), graphQL.requests.map { askedIds(it).size })
        assertEquals("ANIME", graphQL.requests.first().variables!!["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicateIdMalKeepsTheLowestAniListId() = runBlocking {
        val graphQL = FakeGraphQL(
            pages = listOf(page(media(id = 100, idMal = 7), media(id = 50, idMal = 7))),
        )

        val result = bridge(graphQL).hydrateByMalIds(listOf(7), MediaType.ANIME)

        assertTrue(result is NetworkResult.Success)
        assertEquals(50, result.value[7]?.id)
    }

    @Test
    fun idsWithoutAnAniListMatchAreOmitted() = runBlocking {
        val graphQL = FakeGraphQL(
            pages = listOf(page(media(id = 10, idMal = 1))),
        )

        val result = bridge(graphQL).hydrateByMalIds(listOf(1, 2), MediaType.MANGA)

        assertTrue(result is NetworkResult.Success)
        assertEquals(setOf(1), result.value.keys)
        assertEquals(10, result.value[1]?.id)
    }

    @Test
    fun theIdMapIsPersistedForLaterWrites() = runBlocking {
        val store = InMemoryFileStore()
        val graphQL = FakeGraphQL(
            pages = listOf(
                page(media(id = 10, idMal = 1), media(id = 20, idMal = 2)),
                page(media(id = 30, idMal = 3)),
            ),
        )
        val bridge = bridge(graphQL, store)

        bridge.hydrateByMalIds(listOf(1, 2), MediaType.ANIME)
        val first = decodeMap(store.read(idBridgeFileName(MediaType.ANIME)))
        assertEquals(mapOf(1 to 10, 2 to 20), first)

        bridge.hydrateByMalIds(listOf(2, 3), MediaType.ANIME)
        val second = decodeMap(store.read(idBridgeFileName(MediaType.ANIME)))
        assertEquals(mapOf(1 to 10, 2 to 20, 3 to 30), second)
    }

    @Test
    fun aFailedBatchReturnsTheErrorAndDoesNotWriteTheMap() = runBlocking {
        val store = InMemoryFileStore()
        val graphQL = FakeGraphQL(pages = listOf(page()), failureAt = 0)

        val result = bridge(graphQL, store).hydrateByMalIds(listOf(1), MediaType.ANIME)

        assertTrue(result is NetworkResult.Failure)
        assertNull(store.read(idBridgeFileName(MediaType.ANIME)))
    }

    private fun decodeMap(raw: String?): Map<Int, Int> {
        if (raw == null) return emptyMap()
        return AniListJson.decodeFromString(
            MapSerializer(Int.serializer(), Int.serializer()),
            raw,
        )
    }
}
