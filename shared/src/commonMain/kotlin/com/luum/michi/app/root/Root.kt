package com.luum.michi.app.root

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.luum.michi.app.calendar.domain.CalendarRepository
import com.luum.michi.app.calendar.ui.CalendarScreen
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.effectiveScoreFormat
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.favourite.domain.FavouriteRepository
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.parseScoreFormat
import com.luum.michi.app.core.medialist.repository.MirrorOutboxState
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.SortPersistence
import com.luum.michi.app.core.navigation.domain.DetailDestination
import com.luum.michi.app.core.navigation.domain.TabSection
import com.luum.michi.app.core.platform.domain.ShareLauncher
import com.luum.michi.app.core.platform.domain.UrlOpener
import com.luum.michi.app.core.session.domain.AccountEvent
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.discover.domain.DashboardRepository
import com.luum.michi.app.discover.domain.ExploreRepository
import com.luum.michi.app.mediaDetail.domain.character.CharacterDetailRepository
import com.luum.michi.app.mediaDetail.domain.media.AnimeThemesRepository
import com.luum.michi.app.mediaDetail.domain.media.MediaDetailRepository
import com.luum.michi.app.mediaDetail.domain.staff.StaffDetailRepository
import com.luum.michi.app.mediaDetail.domain.studio.StudioDetailRepository
import com.luum.michi.app.mediaList.domain.anime.AnimeListRepository
import com.luum.michi.app.mediaList.domain.manga.MangaListRepository
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncCoordinator
import com.luum.michi.app.mirror.ui.MirrorSyncScreen
import com.luum.michi.app.notifications.domain.NotificationsRepository
import com.luum.michi.app.notifications.ui.NotificationsScreen
import com.luum.michi.app.profile.domain.ProfileRepository
import com.luum.michi.app.root.state.RootOverlay
import com.luum.michi.app.root.state.State
import com.luum.michi.app.root.state.detailSurfaceKey
import com.luum.michi.app.root.state.rememberState
import com.luum.michi.app.root.state.resolveStartDestination
import com.luum.michi.app.root.state.startDestinationChoice
import com.luum.michi.app.settings.ui.AboutScreen
import com.luum.michi.app.settings.ui.SettingsScreen
import kotlinx.coroutines.delay
import com.luum.michi.app.accounts.ui.AccountsScreen
import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.PillShape
import com.luum.michi.app.ui.components.SearchScrim
import com.luum.michi.app.ui.components.ToolbarSearch
import com.luum.michi.app.ui.components.tabFadeSpec
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.language.searchHintFor
import com.luum.michi.app.ui.theme.LocalWarning

/**
 * Slim app orchestrator: owns the [State], derives the topbar per tab and
 * routes the content area. Cross-feature coordination lives here — features
 * never import each other or this package.
 *
 * System-back wiring happens at the platform entries (MainActivity /
 * MainViewController) via [State.applyBackStep]; every in-app affordance
 * (toolbar back, scrim tap) already routes through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Root(
    settingsState: SettingsState,
    capabilities: ProviderCapabilities,
    viewer: Viewer? = null,
    providedState: State? = null,
    strings: LanguageStrings = Strings.current,
    onLogout: () -> Unit = {},
    animeListRepository: AnimeListRepository,
    mangaListRepository: MangaListRepository,
    mediaListEntryRepository: MediaListEntryRepository,
    mediaDetailRepository: MediaDetailRepository,
    animeThemesRepository: AnimeThemesRepository,
    characterDetailRepository: CharacterDetailRepository,
    staffDetailRepository: StaffDetailRepository,
    studioDetailRepository: StudioDetailRepository,
    exploreRepository: ExploreRepository,
    calendarRepository: CalendarRepository,
    dashboardRepository: DashboardRepository,
    profileRepository: ProfileRepository,
    favouriteRepository: FavouriteRepository,
    notificationsRepository: NotificationsRepository,
    mirrorSyncCoordinator: MirrorSyncCoordinator,
    urlOpener: UrlOpener,
    shareLauncher: ShareLauncher,
    sortPersistence: SortPersistence,
    authServices: List<AuthService>,
    loginErrorMessage: String? = null,
    onSignInDismissed: () -> Unit = {},
    isAuthConfigured: Boolean,
    accounts: AccountsUiState = AccountsUiState(
        connected = emptyList(),
        followed = null,
        labels = emptyMap(),
    ),
    mirrorOutbox: MirrorOutboxState = MirrorOutboxState(),
    onConnectAccount: (ProviderId) -> Unit = {},
    onDisconnectAccount: (ProviderId) -> Unit = {},
    onSetFollowed: (ProviderId) -> Unit = {},
    onSetAutoCopy: (Boolean) -> Unit = {},
    onDismissLostAccess: () -> Unit = {},
    onRetryMirror: () -> Unit = {},
    accountEvents: kotlinx.coroutines.flow.Flow<AccountEvent> = kotlinx.coroutines.flow.emptyFlow(),
    autoOpenSignIn: Boolean = false,
    onAutoSignInShown: () -> Unit = {},
    deepLinkDestination: DetailDestination? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    // The "Pestaña al abrir" setting seeds the shell state once (startup only):
    // a later settings change never moves the user. A guest can only land on
    // DISCOVER (the other tabs need a session) and a section id that no longer
    // exists (split-completed off) falls back to ALL. Resolved once here (not
    // in a default argument), so Root's recompositions never re-read the six
    // settings.
    val state = providedState ?: rememberState(
        startDestination = remember {
            resolveStartDestination(
                startTab = settingsState.startTab,
                animeSectionId = settingsState.startAnimeSectionId,
                mangaSectionId = settingsState.startMangaSectionId,
                splitCompletedAnime = settingsState.splitCompletedAnime,
                splitCompletedManga = settingsState.splitCompletedManga,
                isGuest = viewer == null,
            )
        },
    )
    val tab = state.selectedTab
    var accountEvent by remember { mutableStateOf<AccountEvent?>(null) }
    LaunchedEffect(accountEvents) {
        accountEvents.collect { accountEvent = it }
    }
    LaunchedEffect(accountEvent) {
        if (accountEvent != null) {
            delay(3_000)
            accountEvent = null
        }
    }
    val searchTab = state.searchActiveTab
    // The search field is a tab-surface affordance: while a detail, the editor or
    // an above-detail overlay draws over it, the toolbar shows that surface
    // instead (State.isSearchVisible). The search itself stays open.
    val search = searchTab?.takeIf { state.isSearchVisible }?.let {
        ToolbarSearch(
            query = state.searchQuery(it),
            hint = searchHintFor(it, strings).orEmpty(),
            autoFocus = true,
        )
    }
    // Derived, no state of its own: scrim while expanded with an empty query,
    // replaced by the result list on the first letter.
    val activeQuery = searchTab?.let { state.searchQuery(it) }.orEmpty()
    // The query belongs to the tab where search opened: switching tabs
    // mid-search must not filter the new tab with a foreign query.
    val tabQuery = if (searchTab == tab) activeQuery else ""
    val showScrim = state.isSearchVisible && activeQuery.isEmpty()

    val viewerId = viewer?.id

    // "Pestaña al abrir": the settings row stays feature-agnostic, so Root
    // resolves the localized sub-choice (settings never imports a feature) and
    // the section ids are validated against the live lists.
    val startChoice = startDestinationChoice(
        tab = settingsState.startTab,
        catalogType = settingsState.startCatalogType,
        animeSectionId = settingsState.startAnimeSectionId,
        mangaSectionId = settingsState.startMangaSectionId,
        splitCompletedAnime = settingsState.splitCompletedAnime,
        splitCompletedManga = settingsState.splitCompletedManga,
        strings = strings,
    )

    // Per-surface saveable state (scroll positions): Root owns the holder so a
    // tab, each detail entry and the overlays keep their own position. A popped
    // detail entry drops its state (a new entry always starts at the top).
    val surfaceStates = rememberSaveableStateHolder()

    val holders = key(viewer?.provider) {
        rememberRootHolders(
        viewerId = viewerId,
        notificationsEnabled = capabilities.notifications,
        profileDetailsEnabled = capabilities.profileDetails,
        settingsState = settingsState,
        strings = strings,
        animeListRepository = animeListRepository,
        mangaListRepository = mangaListRepository,
        mediaListEntryRepository = mediaListEntryRepository,
        mediaDetailRepository = mediaDetailRepository,
        animeThemesRepository = animeThemesRepository,
        characterDetailRepository = characterDetailRepository,
        staffDetailRepository = staffDetailRepository,
        studioDetailRepository = studioDetailRepository,
        exploreRepository = exploreRepository,
        calendarRepository = calendarRepository,
        dashboardRepository = dashboardRepository,
        profileRepository = profileRepository,
        favouriteRepository = favouriteRepository,
        notificationsRepository = notificationsRepository,
        sortPersistence = sortPersistence,
        mirrorSyncCoordinator = mirrorSyncCoordinator,
        )
    }

    RootEffects(
        state = state,
        holders = holders,
        settingsState = settingsState,
        viewer = viewer,
        sortPersistence = sortPersistence,
        surfaceStates = surfaceStates,
        autoOpenSignIn = autoOpenSignIn,
        onAutoSignInShown = onAutoSignInShown,
        deepLinkDestination = deepLinkDestination,
        onDeepLinkHandled = onDeepLinkHandled,
    )

    Scaffold(
        topBar = {
            RootToolbar(
                state = state,
                tab = tab,
                search = search,
                searchTab = searchTab,
                holders = holders,
                isGuest = viewerId == null,
                showsNotifications = capabilities.notifications,
                showsFavourite = capabilities.favourites,
                strings = strings,
            )
        },
        bottomBar = {
            RootTabBar(tab = tab, state = state, strings = strings)
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Top only: content scrolls behind the floating tab bar.
                .padding(top = padding.calculateTopPadding()),
        ) {
            // Swipe-like directional swap: sign of the tab order only, fixed
            // magnitude — direct initial -> target, never through middle tabs.
            // Tab state drives the transition, never data loads.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = TabSection.entries.indexOf(targetState) >
                        TabSection.entries.indexOf(initialState)
                    val enterFrom = if (forward) 1 else -1
                    (fadeIn(animationSpec = tabFadeSpec()) +
                        slideInHorizontally(animationSpec = tabFadeSpec()) { it / 4 * enterFrom } togetherWith
                        fadeOut(animationSpec = tabFadeSpec()) +
                        slideOutHorizontally(animationSpec = tabFadeSpec()) { -it / 4 * enterFrom }) using
                        SizeTransform(clip = false)
                },
                label = "tab-content",
            ) { activeTab ->
                // One saved surface per view: switching surfaces (tab ↔ detail,
                // detail ↔ detail, overlay) preserves each one's saveable state
                // (scroll) without recreating the holders above. The slot's target
                // tab is part of the key: during a tab transition both slots compose
                // and equal keys would crash.
                val surface = surfaceKey(activeTab, state)
                surfaceStates.SaveableStateProvider(surface) {
                    key(surface) {
                        if (state.isOpen(RootOverlay.Settings)) {
                            if (state.settingsMirrorOpen && holders.mirrorSync != null) {
                                MirrorSyncScreen(
                                    holder = holders.mirrorSync,
                                    bottomPadding = padding.calculateBottomPadding(),
                                    outbox = mirrorOutbox,
                                    onRetry = onRetryMirror,
                                    onApplied = { state.closeSettingsMirror() },
                                )
                            } else if (state.settingsAccountsOpen) {
                                AccountsScreen(
                                    state = accounts,
                                    coordinator = mirrorSyncCoordinator,
                                    previewHolder = holders.mirrorSync,
                                    bottomPadding = padding.calculateBottomPadding(),
                                    onConnect = onConnectAccount,
                                    onDisconnect = onDisconnectAccount,
                                    onSetFollowed = onSetFollowed,
                                    onSetAutoCopy = onSetAutoCopy,
                                    onDismissLostAccess = onDismissLostAccess,
                                    onOpenUrl = { urlOpener.open(it) },
                                    onOpenPreview = { state.openSettingsMirror() },
                                    onLogout = onLogout,
                                )
                            } else if (state.settingsAboutOpen) {
                                AboutScreen(
                                    bottomPadding = padding.calculateBottomPadding(),
                                    onOpenUrl = { urlOpener.open(it) },
                                    onShare = { text, subject ->
                                        shareLauncher.share(text, subject)
                                    },
                                )
                            } else {
                                SettingsScreen(
                                    settingsState = settingsState,
                                    startDestination = startChoice,
                                    viewer = viewer,
                                    accounts = accounts,
                                    onDismissLostAccess = onDismissLostAccess,
                                    onOpenAccounts = { state.openSettingsAccounts() },
                                    onOpenAbout = { state.openSettingsAbout() },
                                    bottomPadding = padding.calculateBottomPadding(),
                                )
                            }
                        } else if (state.isDetailOpen) {
                            // Detail page over the current tab (tab bar hidden):
                            // pushes onto detailStack, back pops one at a time.
                            RootDetailHost(
                                destination = state.currentDetail,
                                holders = holders,
                                state = state,
                                strings = strings,
                                bottomPadding = padding.calculateBottomPadding(),
                                urlOpener = urlOpener,
                                viewerScoreFormat = capabilities.effectiveScoreFormat(
                                    parseScoreFormat(viewer?.scoreFormat),
                                ),
                            )
                        } else if (state.isOpen(RootOverlay.Calendar)) {
                            // Airing schedule overlay (fullscreen, tab bar hidden):
                            // public feed, so guests render the same surface.
                            CalendarScreen(
                                holder = holders.calendar,
                                onRetry = { holders.calendar.reloadSelected() },
                                bottomPadding = padding.calculateBottomPadding(),
                                isRefreshing = holders.calendar.isLoading,
                                onRefresh = { holders.calendar.reloadSelected() },
                                isFilterOpen = state.isOpen(RootOverlay.CalendarFilter),
                                onDismissFilter = { state.close(RootOverlay.CalendarFilter) },
                                onOpenDetail = { state.openMedia(aniListRef(it)) },
                                onOpenUrl = { urlOpener.open(it) },
                            )
                        } else if (state.isOpen(RootOverlay.Notifications) &&
                            holders.notifications != null
                        ) {
                            // Notifications page (fullscreen, tab bar hidden), over
                            // the current tab. A media notification pushes its
                            // detail above it, so back returns to the feed.
                            NotificationsScreen(
                                holder = holders.notifications,
                                bottomPadding = padding.calculateBottomPadding(),
                                onRetry = { holders.notifications.refresh() },
                                onRefresh = { holders.notifications.refresh() },
                                onOpenMedia = { state.openMedia(aniListRef(it)) },
                                onOpenCharacter = { state.openCharacter(aniListRef(it)) },
                                onOpenStaff = { state.openStaff(aniListRef(it)) },
                                onOpenUrl = { urlOpener.open(it) },
                            )
                        } else {
                            RootTabContent(
                                activeTab = activeTab,
                                state = state,
                                holders = holders,
                                settingsState = settingsState,
                                capabilities = capabilities,
                                viewer = viewer,
                                viewerId = viewerId,
                                tabQuery = tabQuery,
                                strings = strings,
                                bottomPadding = padding.calculateBottomPadding(),
                                onOpenUrl = { urlOpener.open(it) },
                            )
                        }
                    }
                }
            }
            if (showScrim) {
                SearchScrim(onDismiss = { state.applyBackStep() })
            }
            RootModals(
                state = state,
                holders = holders,
                viewerId = viewerId,
                settingsState = settingsState,
                capabilities = capabilities,
                authServices = authServices,
                loginErrorMessage = loginErrorMessage,
                onSignInDismissed = onSignInDismissed,
                isAuthConfigured = isAuthConfigured,
                mediaListEntryRepository = mediaListEntryRepository,
                mediaDetailRepository = mediaDetailRepository,
                urlOpener = urlOpener,
                shareLauncher = shareLauncher,
                strings = strings,
            )
            accountEvent?.let { event ->
                AccountEventPill(
                    event = event,
                    providerLabel = accounts.label(event.provider),
                    strings = strings,
                    onDismiss = { accountEvent = null },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountEventPill(
    event: AccountEvent,
    providerLabel: String,
    strings: LanguageStrings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, text) = when (event) {
        is AccountEvent.Connected -> AppIcons.Check to strings.accountsEventConnected(providerLabel)
        is AccountEvent.Disconnected ->
            AppIcons.Clear to strings.accountsEventDisconnected(providerLabel)
        is AccountEvent.LostAccess ->
            AppIcons.Warning to strings.accountsEventLostAccess(providerLabel)
        is AccountEvent.FollowedChanged ->
            AppIcons.ChevronRight to strings.accountsEventFollowed(providerLabel)
    }
    val warning = event is AccountEvent.LostAccess
    Surface(
        modifier = modifier
            .clip(PillShape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(onClick = onDismiss),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (warning) LocalWarning.current else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (warning) LocalWarning.current else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Which saved surface the content area shows: the slot's tab + the view. */
private fun surfaceKey(tab: TabSection, state: State): String = when {
    state.isOpen(RootOverlay.Settings) && state.settingsMirrorOpen -> "${tab.name}:settings-mirror"
    state.isOpen(RootOverlay.Settings) && state.settingsAccountsOpen -> "${tab.name}:settings-accounts"
    state.isOpen(RootOverlay.Settings) && state.settingsAboutOpen -> "${tab.name}:settings-about"
    state.isOpen(RootOverlay.Settings) -> "${tab.name}:settings"
    state.isDetailOpen -> detailSurfaceKey(tab, state.currentDetailEntryId)
    state.isOpen(RootOverlay.Calendar) -> "${tab.name}:calendar"
    state.isOpen(RootOverlay.Notifications) -> "${tab.name}:notifications"
    else -> "${tab.name}:tab"
}
