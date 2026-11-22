package com.luum.michi.app.core

import com.luum.michi.app.FakeGraphQL
import com.luum.michi.app.core.media.domain.MalRef
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
    fun batchesHalfAPageSoSharedIdMalsFit() = runBlocking {
        val graphQL = FakeGraphQL(
            pages = List(5) { page() },
        )
        val ids = (1..120).toList()

        val result = bridge(graphQL).hydrateByMalIds(ids, MediaType.ANIME)

        assertTrue(result is NetworkResult.Success)
        assertEquals(5, graphQL.calls)
        assertEquals(listOf(25, 25, 25, 25, 20), graphQL.requests.map { askedIds(it).size })
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

    @Test
    fun resolveMalUsesTheDiskMapBeforeAskingAniList() = runBlocking {
        val store = InMemoryFileStore()
        store.put(
            idBridgeFileName(MediaType.ANIME),
            AniListJson.encodeToString(
                MapSerializer(Int.serializer(), Int.serializer()),
                mapOf(7 to 100),
            ),
        )
        val graphQL = FakeGraphQL(emptyList())

        val result = bridge(graphQL, store).resolveMal(100)

        assertEquals(MalRef(malId = 7, type = MediaType.ANIME), (result as NetworkResult.Success).value)
        assertEquals(0, graphQL.calls)
    }

    @Test
    fun resolveMalFallsBackToAniListAndPersistsTheMapping() = runBlocking {
        val store = InMemoryFileStore()
        val graphQL = FakeGraphQL(
            pages = listOf(
                buildJsonObject {
                    put("Media", buildJsonObject {
                        put("idMal", 7)
                        put("type", "ANIME")
                    })
                },
            ),
        )

        val result = bridge(graphQL, store).resolveMal(100)

        assertEquals(MalRef(malId = 7, type = MediaType.ANIME), (result as NetworkResult.Success).value)
        assertEquals(1, graphQL.calls)
        assertEquals(mapOf(7 to 100), decodeMap(store.read(idBridgeFileName(MediaType.ANIME))))
    }

    @Test
    fun resolveMalReturnsNullWhenAniListHasNoMalId() = runBlocking {
        val store = InMemoryFileStore()
        val graphQL = FakeGraphQL(
            pages = listOf(
                buildJsonObject {
                    put("Media", buildJsonObject {
                        put("idMal", kotlinx.serialization.json.JsonNull)
                        put("type", "ANIME")
                    })
                },
            ),
        )

        val result = bridge(graphQL, store).resolveMal(100)

        assertNull((result as NetworkResult.Success).value)
        assertNull(store.read(idBridgeFileName(MediaType.ANIME)))
    }

    @Test
    fun rememberMalRefWritesAndCachedIdMapReads() = runBlocking {
        val store = InMemoryFileStore()
        val bridge = bridge(FakeGraphQL(emptyList()), store)

        bridge.rememberMalRef(100, MalRef(malId = 7, type = MediaType.MANGA))

        assertEquals(mapOf(7 to 100), bridge.cachedIdMap(MediaType.MANGA))
        assertEquals(emptyMap(), bridge.cachedIdMap(MediaType.ANIME))
    }

    @Test
    fun rememberMalRefKeepsTheLowerAniListIdForASharedIdMal() = runBlocking {
        val store = InMemoryFileStore()
        val bridge = bridge(FakeGraphQL(emptyList()), store)

        bridge.rememberMalRef(50, MalRef(malId = 7, type = MediaType.ANIME))
        bridge.rememberMalRef(100, MalRef(malId = 7, type = MediaType.ANIME))
        assertEquals(mapOf(7 to 50), bridge.cachedIdMap(MediaType.ANIME))

        bridge.rememberMalRef(20, MalRef(malId = 7, type = MediaType.ANIME))
        assertEquals(mapOf(7 to 20), bridge.cachedIdMap(MediaType.ANIME))
    }

    private fun decodeMap(raw: String?): Map<Int, Int> {
        if (raw == null) return emptyMap()
        return AniListJson.decodeFromString(
            MapSerializer(Int.serializer(), Int.serializer()),
            raw,
        )
    }
}
