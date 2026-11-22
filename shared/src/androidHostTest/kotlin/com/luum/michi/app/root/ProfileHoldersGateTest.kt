package com.luum.michi.app.root

import com.luum.michi.app.core.favourite.domain.FavouriteRepository
import com.luum.michi.app.core.favourite.domain.FavouriteTarget
import com.luum.michi.app.core.model.Page
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.profile.domain.ProfileData
import com.luum.michi.app.profile.domain.ProfileRepository
import com.luum.michi.app.profile.domain.model.ProfileFavorite
import com.luum.michi.app.profile.domain.model.ProfileFavoritesCategory
import com.luum.michi.app.profile.domain.model.ProfileFollow
import com.luum.michi.app.profile.domain.model.ProfileFollowsKind
import com.luum.michi.app.profile.domain.model.ProfileReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class CountingProfileRepository : ProfileRepository {
    var loadCalls = 0
        private set

    override suspend fun loadProfile(userId: Int): NetworkResult<ProfileData> {
        loadCalls++
        return NetworkResult.Failure(NetworkError.NoConnection)
    }

    override suspend fun loadCachedProfile(userId: Int): ProfileData? = null

    override suspend fun saveCachedProfile(userId: Int, data: ProfileData) = Unit

    override suspend fun loadFavoritesPage(
        userId: Int,
        category: ProfileFavoritesCategory,
        page: Int,
    ): NetworkResult<Page<ProfileFavorite>> = NetworkResult.Failure(NetworkError.NoConnection)

    override suspend fun loadFollowsPage(
        userId: Int,
        kind: ProfileFollowsKind,
        page: Int,
    ): NetworkResult<Page<ProfileFollow>> = NetworkResult.Failure(NetworkError.NoConnection)

    override suspend fun loadReviewsPage(
        userId: Int,
        page: Int,
    ): NetworkResult<Page<ProfileReview>> = NetworkResult.Failure(NetworkError.NoConnection)
}

private class NoopFavouriteRepository : FavouriteRepository {
    private val state = MutableStateFlow(0)
    override val revision: StateFlow<Int> = state
    override suspend fun toggleFavourite(target: FavouriteTarget): NetworkResult<Unit> =
        NetworkResult.Success(Unit)
}

class ProfileHoldersGateTest {

    private fun holders(profileDetailsEnabled: Boolean): ProfileHolders {
        val repository = CountingProfileRepository()
        val holders = createProfileHolders(
            viewerId = 1,
            profileDetailsEnabled = profileDetailsEnabled,
            profileRepository = repository,
            favouriteRepository = NoopFavouriteRepository(),
            notificationsHolder = null,
            favouriteRouter = { _, _ -> },
            profileScope = CoroutineScope(Dispatchers.Unconfined),
            profileFavoritesScope = CoroutineScope(Dispatchers.Unconfined),
            profileFollowsScope = CoroutineScope(Dispatchers.Unconfined),
            profileReviewsScope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(0, repository.loadCalls, "creating holders must never request the profile")
        return holders
    }

    @Test
    fun aProviderWithoutProfileDetailsGetsNoProfileHolders() {
        val holders = holders(profileDetailsEnabled = false)

        assertNull(holders.profile)
        assertNull(holders.favorites)
        assertNull(holders.follows)
        assertNull(holders.reviews)
    }

    @Test
    fun aniListStillGetsEveryProfileHolder() {
        val holders = holders(profileDetailsEnabled = true)

        assertNotNull(holders.profile)
        assertNotNull(holders.favorites)
        assertNotNull(holders.follows)
        assertNotNull(holders.reviews)
    }

    @Test
    fun aGuestNeverGetsProfileHolders() {
        val holders = createProfileHolders(
            viewerId = null,
            profileDetailsEnabled = true,
            profileRepository = CountingProfileRepository(),
            favouriteRepository = NoopFavouriteRepository(),
            notificationsHolder = null,
            favouriteRouter = { _, _ -> },
            profileScope = CoroutineScope(Dispatchers.Unconfined),
            profileFavoritesScope = CoroutineScope(Dispatchers.Unconfined),
            profileFollowsScope = CoroutineScope(Dispatchers.Unconfined),
            profileReviewsScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertNull(holders.profile)
        assertNull(holders.favorites)
        assertNull(holders.follows)
        assertNull(holders.reviews)
    }
}
