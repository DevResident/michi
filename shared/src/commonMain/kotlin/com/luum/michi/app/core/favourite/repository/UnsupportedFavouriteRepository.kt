package com.luum.michi.app.core.favourite.repository

import com.luum.michi.app.core.favourite.domain.FavouriteRepository
import com.luum.michi.app.core.favourite.domain.FavouriteTarget
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The linked-account writer never toggles favourites. It still needs the
 * repository type, so this instance fails loudly instead of being nullable.
 */
internal class UnsupportedFavouriteRepository : FavouriteRepository {
    private val state = MutableStateFlow(0)
    override val revision: StateFlow<Int> = state.asStateFlow()

    override suspend fun toggleFavourite(target: FavouriteTarget): NetworkResult<Unit> =
        NetworkResult.Failure(
            NetworkError.Http(400, "The linked account does not support favourites"),
        )
}
