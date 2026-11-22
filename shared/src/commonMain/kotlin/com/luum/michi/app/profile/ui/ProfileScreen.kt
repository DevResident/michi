package com.luum.michi.app.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.model.compactCount
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.profile.domain.model.ProfileFavorite
import com.luum.michi.app.profile.domain.model.ProfileFavoritesCategory
import com.luum.michi.app.profile.domain.model.ProfileStats
import com.luum.michi.app.profile.domain.model.itemsFor
import com.luum.michi.app.profile.domain.model.nonEmptyCategories
import com.luum.michi.app.profile.ui.model.title
import com.luum.michi.app.profile.ui.model.toPersonItem
import com.luum.michi.app.profile.ui.model.toRailItem
import com.luum.michi.app.profile.ui.state.ProfileStateHolder
import com.luum.michi.app.ui.components.Avatar
import com.luum.michi.app.ui.components.AvatarSize
import com.luum.michi.app.ui.components.HeroBanner
import com.luum.michi.app.ui.components.HeroBannerHeight
import com.luum.michi.app.ui.components.LoadStateContainer
import com.luum.michi.app.ui.components.MediaCardRail
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.PersonCardRail
import com.luum.michi.app.ui.components.RailHeader
import com.luum.michi.app.ui.components.RailHorizontalPadding
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.language.Strings

/**
 * PROFILE tab: the viewer's hero (banner, avatar over its bottom edge, name and
 * frameless counters) over their favourites, one rail per category with a
 * "View all" page. Anime/manga use the shared [MediaCardRail], characters/staff
 * the simple [PersonCardRail], and every card carries a second line. With an
 * AniList session the viewer's own profile is loaded ([ProfileStateHolder.load]
 * with the viewer id); with a provider that has no profile details (MAL) the
 * holder is null and only the avatar/name hero is painted — no AniList request
 * and no AniList user id. The name/avatar/banner come from the already-loaded
 * [Viewer] — no extra request.
 * Read-only: favourites are removed from each category's "View all" page.
 */
@Composable
internal fun ProfileScreen(
    holder: ProfileStateHolder?,
    viewer: Viewer,
    strings: LanguageStrings = Strings.current,
    bottomPadding: Dp = 0.dp,
    onOpenFavorite: (ProfileFavoritesCategory, Int) -> Unit,
    onViewAll: (ProfileFavoritesCategory) -> Unit,
    onOpenStatistics: (MediaType) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit,
    onOpenReviews: () -> Unit,
    showsFavourites: Boolean = true,
    showsSocial: Boolean = true,
    showsStatistics: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (holder == null) {
        // Provider without profile details (MAL): avatar + name only. No
        // AniList request is made and no AniList user id is used.
        ProfileHeroOnly(
            viewer = viewer,
            strings = strings,
            bottomPadding = bottomPadding,
            modifier = modifier,
        )
        return
    }

    // Explicit load on entry: the TTL dedups re-entries (0 requests while fresh).
    LaunchedEffect(holder, viewer.id) {
        holder.load(viewer.id)
    }
    ProfileContent(
        holder = holder,
        viewer = viewer,
        strings = strings,
        bottomPadding = bottomPadding,
        onOpenFavorite = onOpenFavorite,
        onViewAll = onViewAll,
        onOpenStatistics = onOpenStatistics,
        onOpenFollowing = onOpenFollowing,
        onOpenFollowers = onOpenFollowers,
        onOpenReviews = onOpenReviews,
        showsFavourites = showsFavourites,
        showsSocial = showsSocial,
        showsStatistics = showsStatistics,
        modifier = modifier,
    )
}

/** Hero-only profile for providers whose account has no AniList profile data. */
@Composable
private fun ProfileHeroOnly(
    viewer: Viewer,
    strings: LanguageStrings,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp + bottomPadding),
    ) {
        item(key = "profile-header") {
            ProfileHeader(
                viewer = viewer,
                stats = null,
                strings = strings,
                onOpenStatistics = {},
                onOpenFollowing = {},
                onOpenFollowers = {},
                showsSocial = false,
                showsStatistics = false,
                showBanner = false,
                showCounters = false,
            )
        }
    }
}

@Composable
private fun ProfileContent(
    holder: ProfileStateHolder,
    viewer: Viewer,
    strings: LanguageStrings,
    bottomPadding: Dp,
    onOpenFavorite: (ProfileFavoritesCategory, Int) -> Unit,
    onViewAll: (ProfileFavoritesCategory) -> Unit,
    onOpenStatistics: (MediaType) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit,
    onOpenReviews: () -> Unit,
    showsFavourites: Boolean,
    showsSocial: Boolean,
    showsStatistics: Boolean,
    modifier: Modifier = Modifier,
) {
    val errorMessage = holder.error?.let { strings.networkErrorMessage(it) }
    val favorites = holder.favorites
    val categories = favorites.nonEmptyCategories()
    LoadStateContainer(
        hasContent = holder.hasContent,
        // Pull-to-refresh drives the refresh branch (keeps the profile on screen).
        isLoading = holder.isLoading || holder.isRefreshing,
        errorMessage = errorMessage,
        loadingLabel = strings.profileLoadingLabel,
        waitingLabel = strings.networkThrottleWaitingLabel,
        errorTitle = strings.profileErrorLabel,
        retryLabel = strings.retryAction,
        onRetry = { holder.refresh() },
        modifier = modifier.fillMaxSize(),
        // With the profile already on screen, a failed load is a compact line.
        refreshError = if (holder.hasContent) errorMessage else null,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "profile-header") {
                ProfileHeader(
                    viewer = viewer,
                    stats = holder.stats,
                    strings = strings,
                    onOpenStatistics = onOpenStatistics,
                    onOpenFollowing = onOpenFollowing,
                    onOpenFollowers = onOpenFollowers,
                    showsSocial = showsSocial,
                    showsStatistics = showsStatistics,
                )
            }
            if (showsSocial) {
                item(key = "profile-reviews") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        OptionGroup(title = null) {
                            SettingsRow(
                                label = strings.profileReviewsTitle,
                                value = "",
                                onClick = onOpenReviews,
                                divider = false,
                            )
                        }
                    }
                }
            }
            if (showsFavourites) {
                if (categories.isEmpty()) {
                    item(key = "profile-favorites-empty") {
                        Text(
                            text = strings.profileFavoritesGridEmptyLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                categories.forEach { category ->
                    val items = favorites.itemsFor(category)
                    item(key = "profile-favorites-${category.name}") {
                        when (category) {
                            ProfileFavoritesCategory.ANIME,
                            ProfileFavoritesCategory.MANGA -> MediaCardRail(
                                title = category.title(strings),
                                items = items.map { it.toRailItem() },
                                onSelect = { id -> onOpenFavorite(category, id) },
                                onViewAll = { onViewAll(category) },
                                viewAllLabel = strings.viewAllAction,
                            )
                            ProfileFavoritesCategory.CHARACTERS,
                            ProfileFavoritesCategory.STAFF -> PersonCardRail(
                                title = category.title(strings),
                                items = items.map { it.toPersonItem() },
                                onSelectPrimary = { id -> onOpenFavorite(category, id) },
                                onViewAll = { onViewAll(category) },
                                viewAllLabel = strings.viewAllAction,
                            )
                            ProfileFavoritesCategory.STUDIOS -> ProfileStudioSection(
                                items = items,
                                title = category.title(strings),
                                viewAllLabel = strings.viewAllAction,
                                onSelect = { id -> onOpenFavorite(category, id) },
                                onViewAll = { onViewAll(category) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Hero header: the viewer's banner, the avatar over its bottom edge with the
 *  name to the right, and the four frameless counters below. Each counter is a
 *  button that opens Statistics (anime/manga) or the following/followers list. */
@Composable
private fun ProfileHeader(
    viewer: Viewer,
    stats: ProfileStats?,
    strings: LanguageStrings,
    onOpenStatistics: (MediaType) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit,
    showsSocial: Boolean,
    showsStatistics: Boolean,
    showBanner: Boolean = true,
    showCounters: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showBanner) {
            Box(modifier = Modifier.fillMaxWidth()) {
                HeroBanner(
                    bannerUrl = viewer.bannerUrl,
                    paletteHex = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HeroBannerHeight),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = HeroBannerHeight - AvatarSize.Header / 2,
                        ),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Avatar(
                        imageUrl = viewer.avatarUrl,
                        initials = viewer.initials(),
                        size = AvatarSize.Header,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = viewer.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp),
                    )
                }
            }
        } else {
            // MAL has no banner: keep the same avatar/name row without the
            // 180dp placeholder and without the counters row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    imageUrl = viewer.avatarUrl,
                    initials = viewer.initials(),
                    size = AvatarSize.Header,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = viewer.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showCounters && stats != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                ProfileStatColumn(
                    value = stats.animeCount.compactCount(),
                    label = strings.animeLabel,
                    onClick = if (showsStatistics) {
                        ({ onOpenStatistics(MediaType.ANIME) })
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                ProfileStatColumn(
                    value = stats.mangaCount.compactCount(),
                    label = strings.mangaLabel,
                    onClick = if (showsStatistics) {
                        ({ onOpenStatistics(MediaType.MANGA) })
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                ProfileStatColumn(
                    value = stats.followingCount.compactCount(),
                    label = strings.profileFollowingLabel,
                    onClick = if (showsSocial) onOpenFollowing else null,
                    modifier = Modifier.weight(1f),
                )
                ProfileStatColumn(
                    value = stats.followersCount.compactCount(),
                    label = strings.profileFollowersLabel,
                    onClick = if (showsSocial) onOpenFollowers else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One tappable counter: a 48dp-minimum touch target announced as a button. */
@Composable
private fun ProfileStatColumn(
    value: String,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Studio favourites: no image on AniList, so they are settings-style rows (the
 * same look as Explore's studio results) under a rail header with "View all".
 */
@Composable
private fun ProfileStudioSection(
    items: List<ProfileFavorite>,
    title: String,
    viewAllLabel: String,
    onSelect: (Int) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        RailHeader(
            title = title,
            viewAllLabel = viewAllLabel,
            onViewAll = onViewAll,
        )
        Column(modifier = Modifier.padding(horizontal = RailHorizontalPadding)) {
            OptionGroup(title = null) {
                items.forEachIndexed { index, studio ->
                    SettingsRow(
                        label = studio.name,
                        value = "",
                        onClick = { onSelect(studio.id) },
                        divider = index > 0,
                    )
                }
            }
        }
    }
}

/** Initials fallback for the avatar: first letters of the first two words. */
private fun Viewer.initials(): String =
    name.split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { name.take(1).uppercase() }
