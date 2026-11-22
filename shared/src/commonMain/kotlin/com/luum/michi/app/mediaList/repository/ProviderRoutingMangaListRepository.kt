package com.luum.michi.app.mediaList.repository

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.domain.manga.MangaListRepository
import com.luum.michi.app.mediaList.domain.manga.model.MangaListEntry

/** Sends each list call to the provider that owns the current session. */
internal class ProviderRoutingMangaListRepository(
    private val activeProvider: () -> ProviderId,
    private val aniList: MangaListRepository,
    private val mal: MangaListRepository,
) : MangaListRepository {

    private fun target(): MangaListRepository =
        if (activeProvider() == ProviderId.Mal) mal else aniList

    override suspend fun loadList(
        userId: Int,
        splitCompleted: Boolean,
    ): NetworkResult<List<MangaListEntry>> = target().loadList(userId, splitCompleted)

    override suspend fun loadCachedList(
        userId: Int,
        splitCompleted: Boolean,
    ): List<MangaListEntry>? = target().loadCachedList(userId, splitCompleted)
}
