package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.mediaList.domain.anime.AnimeListRepository
import com.luum.michi.app.mediaList.domain.anime.model.AnimeListEntry
import com.luum.michi.app.mediaList.repository.anime.toAnimeListEntry

internal class MalAnimeListRepository(
    private val loader: MalListLoader,
) : AnimeListRepository {

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
        forceHydration: Boolean,
    ): NetworkResult<List<AnimeListEntry>> =
        loader.load(MediaType.ANIME, userId, forceHydration).map { entries ->
            entries.mapIndexed { index, entry -> entry.toAnimeListEntry(index, splitCompleted) }
        }

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<AnimeListEntry>? =
        loader.cached(MediaType.ANIME, userId)?.mapIndexed { index, entry ->
            entry.toAnimeListEntry(index, splitCompleted)
        }
}
