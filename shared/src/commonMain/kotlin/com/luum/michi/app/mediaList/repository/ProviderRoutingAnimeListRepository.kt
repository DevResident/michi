package com.luum.michi.app.mediaList.repository

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.domain.anime.AnimeListRepository
import com.luum.michi.app.mediaList.domain.anime.model.AnimeListEntry

/** Sends each list call to the provider that owns the current session. */
internal class ProviderRoutingAnimeListRepository(
    private val activeProvider: () -> ProviderId,
    private val aniList: AnimeListRepository,
    private val mal: AnimeListRepository,
) : AnimeListRepository {

    private fun target(): AnimeListRepository =
        if (activeProvider() == ProviderId.Mal) mal else aniList

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
        forceHydration: Boolean,
    ): NetworkResult<List<AnimeListEntry>> =
        target().loadList(userId, splitCompleted, forceHydration)

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<AnimeListEntry>? = target().loadCachedList(userId, splitCompleted)
}
