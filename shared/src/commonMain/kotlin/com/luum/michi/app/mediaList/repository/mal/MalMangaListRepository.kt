package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.mediaList.domain.manga.MangaListRepository
import com.luum.michi.app.mediaList.domain.manga.model.MangaListEntry
import com.luum.michi.app.mediaList.repository.manga.toMangaListEntry

internal class MalMangaListRepository(
    private val loader: MalListLoader,
) : MangaListRepository {

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
        forceHydration: Boolean,
    ): NetworkResult<List<MangaListEntry>> =
        loader.load(MediaType.MANGA, userId, forceHydration).map { entries ->
            entries.mapIndexed { index, entry -> entry.toMangaListEntry(index, splitCompleted) }
        }

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<MangaListEntry>? =
        loader.cached(MediaType.MANGA, userId)?.mapIndexed { index, entry ->
            entry.toMangaListEntry(index, splitCompleted)
        }
}
