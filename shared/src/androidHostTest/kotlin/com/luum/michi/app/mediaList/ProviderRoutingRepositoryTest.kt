package com.luum.michi.app.mediaList

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.domain.anime.AnimeListRepository
import com.luum.michi.app.mediaList.domain.anime.model.AnimeListEntry
import com.luum.michi.app.mediaList.domain.manga.MangaListRepository
import com.luum.michi.app.mediaList.domain.manga.model.MangaListEntry
import com.luum.michi.app.mediaList.repository.ProviderRoutingAnimeListRepository
import com.luum.michi.app.mediaList.repository.ProviderRoutingMangaListRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingAnimeRepository : AnimeListRepository {
    var loadCalls = 0
        private set
    var cachedCalls = 0
        private set

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
    ): NetworkResult<List<AnimeListEntry>> {
        loadCalls++
        return NetworkResult.Success(emptyList())
    }

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<AnimeListEntry>? {
        cachedCalls++
        return emptyList()
    }
}

private class RecordingMangaRepository : MangaListRepository {
    var loadCalls = 0
        private set
    var cachedCalls = 0
        private set

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
    ): NetworkResult<List<MangaListEntry>> {
        loadCalls++
        return NetworkResult.Success(emptyList())
    }

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<MangaListEntry>? {
        cachedCalls++
        return emptyList()
    }
}

class ProviderRoutingRepositoryTest {

    @Test
    fun animeFollowsTheActiveProvider() = runBlocking {
        var active = ProviderId.AniList
        val aniList = RecordingAnimeRepository()
        val mal = RecordingAnimeRepository()
        val router = ProviderRoutingAnimeListRepository(
            activeProvider = { active },
            aniList = aniList,
            mal = mal,
        )

        router.loadList(1)
        router.loadCachedList(1)
        assertEquals(1, aniList.loadCalls)
        assertEquals(1, aniList.cachedCalls)
        assertEquals(0, mal.loadCalls)

        active = ProviderId.Mal
        router.loadList(1)
        router.loadCachedList(1)
        assertEquals(1, aniList.loadCalls)
        assertEquals(1, mal.loadCalls)
        assertEquals(1, mal.cachedCalls)
    }

    @Test
    fun mangaFollowsTheActiveProvider() = runBlocking {
        var active = ProviderId.Mal
        val aniList = RecordingMangaRepository()
        val mal = RecordingMangaRepository()
        val router = ProviderRoutingMangaListRepository(
            activeProvider = { active },
            aniList = aniList,
            mal = mal,
        )

        router.loadList(1)
        assertEquals(0, aniList.loadCalls)
        assertEquals(1, mal.loadCalls)

        active = ProviderId.AniList
        router.loadList(1)
        assertEquals(1, aniList.loadCalls)
    }
}
