package com.luum.michi.app

import com.luum.michi.app.core.media.domain.LocalDisplayNames
import com.luum.michi.app.settings.domain.model.toNamePick
import com.luum.michi.app.settings.domain.model.toTitlePick
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.language.domain.getLanguageStrings
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.session.domain.SessionState
import com.luum.michi.app.core.storage.domain.SettingsStoreKeys
import com.luum.michi.app.core.medialist.repository.StoreSortPersistence
import com.luum.michi.app.root.AuthService
import com.luum.michi.app.root.Root
import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.settings.ui.state.rememberSettingsState
import com.luum.michi.app.ui.components.MessagePanel
import com.luum.michi.app.ui.components.tabFadeSpec
import com.luum.michi.app.ui.installImageLoader
import com.luum.michi.app.ui.LocalImageQuality
import com.luum.michi.app.core.model.resolved
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.LocalStrings
import com.luum.michi.app.ui.network.LocalNetworkThrottle
import com.luum.michi.app.ui.testTagsAsResourceIds
import com.luum.michi.app.ui.theme.PlatformThemeModeEffect
import com.luum.michi.app.ui.theme.ProviderBrands
import com.luum.michi.app.ui.theme.Theme
import kotlinx.coroutines.launch

/**
 * App entry: owns theme (palette + mode + font) + language state and routes
 * between the auth landing and the shell based on [SessionState].
 * Called once from each platform entry.
 */
@Composable
fun App(
    dependencies: MichiDependencies,
) {
    // Coil setup + reactive network metering for the AUTO image-quality mode.
    installImageLoader()
    val unmeteredConnection by dependencies.connectivity.isUnmetered.collectAsState()
    val store = dependencies.settingsStore
    val session by dependencies.sessionManager.state.collectAsState()
    val lastLoginError by dependencies.sessionManager.lastLoginError.collectAsState()
    val connectedAccounts by dependencies.sessionManager.connected.collectAsState()
    val followedProvider by dependencies.sessionManager.followed.collectAsState()
    val linkedLostAccess by dependencies.linkedAccountManager.lostAccess.collectAsState()
    val linkedAutoCopy by dependencies.linkedAccountManager.autoCopy.collectAsState()
    val linkError by dependencies.linkedAccountManager.linkError.collectAsState()
    val mirrorOutbox by dependencies.mirrorController.state.collectAsState()
    val deepLinkDestination by dependencies.pendingDeepLink.collectAsState()
    val scope = rememberCoroutineScope()

    // Account capabilities of the active provider. Guests use AniList (the
    // account-only features they cannot use are already blocked by isGuest).
    val capabilities = (session as? SessionState.Authenticated)
        ?.let { dependencies.capabilitiesFor(it.provider) }
        ?: ProviderCapabilities.AniList

    // Synced settings holder: one per session. Guests get canSync = false
    // (local-only, never touches the network); login/logout recreates it. It
    // also owns the app appearance prefs (language, theme, font, surfaces),
    // hydrated from the store once and persisted on every change.
    val isAuthenticated = session is SessionState.Authenticated
    val settingsState = rememberSettingsState(
        repository = dependencies.settingsRepository,
        store = dependencies.settingsStore,
        scope = scope,
        canSync = isAuthenticated && capabilities.remoteSettings,
        // MAL (authenticated, no remote settings) keeps 18+ as a local pref;
        // guests stay off with no toggle.
        useLocalAdultContent = isAuthenticated && !capabilities.remoteSettings,
    )

    val strings = getLanguageStrings(settingsState.language)

    // MAL reads AniList anonymously (userPreferred is always romaji), so the
    // app applies the local title/staff-name languages itself. With AniList
    // (or as a guest) AniList's userPreferred already follows the account.
    // Assigned during composition (idempotent, not state) so it already holds
    // when the first list or detail is mapped in an effect.
    val localTitlePick = if (settingsState.useLocalAdultContent) settingsState.titleLanguage.toTitlePick() else null
    val localNamePick = if (settingsState.useLocalAdultContent) settingsState.staffNameLanguage.toNamePick() else null
    LocalDisplayNames.title = localTitlePick
    LocalDisplayNames.name = localNamePick

    // Keep Android's per-app night mode in sync with the user's choice: the
    // next cold start's splash/window background then uses the same mode.
    PlatformThemeModeEffect(settingsState.themeType)

    // Login services: shared by the sign-in modal everywhere it opens. The
    // visible AniList/MAL labels are translated actions; any future provider
    // falls back to its brand name.
    val authServices = dependencies.authProviders.map { provider ->
        val isAniList = provider.id == ProviderId.AniList
        val isMal = provider.id == ProviderId.Mal
        AuthService(
            id = provider.id.value,
            name = provider.label,
            label = when {
                isAniList -> strings.authLoginAction
                isMal -> strings.authLoginMalAction
                else -> provider.label
            },
            onLogin = {
                dependencies.sessionManager.clearLoginError()
                // Same pending-link path as Accounts: a callback can never
                // replace a session the user did not ask to change.
                dependencies.beginLink(provider.id)
            },
            icon = when {
                isAniList -> AppIcons.AniList
                isMal -> AppIcons.Mal
                else -> null
            },
            brandHex = ProviderBrands.hex(provider.id),
        )
    }
    val loginErrorMessage = lastLoginError?.let { failed ->
        authServices.firstOrNull { it.id == failed.value }?.name
            ?.let { strings.authLoginFailed.replace("%s", it) }
    }
    val isAuthConfigured = dependencies.authProviders.any { it.isConfigured }
    val authenticatedProvider = (session as? SessionState.Authenticated)?.provider
    val labels = dependencies.authProviders
        .filter { it.isConfigured }
        .associate { it.id to it.label }
    val accountsUiState = AccountsUiState(
        connected = connectedAccounts,
        followed = followedProvider,
        labels = labels,
        autoCopy = linkedAutoCopy,
        lostAccess = linkedLostAccess,
        linkError = linkError,
        outbox = mirrorOutbox,
    )
    // Sort memory backing (Settings persist toggle, per tab): store-owned,
    // session-independent — Root hydrates holders from it, holders save
    // into it. Local-only, like theme/language.
    val sortPersistence = remember { StoreSortPersistence(store) }

    // Synced prefs follow the viewer. This lives here (not in Root) so it
    // survives Root being recomposed from scratch (login → error → retry):
    // seed from the Viewer + refresh the rest on authenticate; clear on
    // user → guest so a guest never inherits the account's 18+ gate.
    val settingsViewer = (session as? SessionState.Authenticated)?.viewer
    val settingsViewerKey = settingsViewer?.let { it.id to it.provider }
    var lastSettingsViewerKey by remember { mutableStateOf<Pair<Int, ProviderId>?>(null) }
    LaunchedEffect(settingsViewerKey) {
        val previous = lastSettingsViewerKey
        if (settingsViewerKey != previous) {
            if (settingsViewer != null) {
                settingsState.seedFrom(settingsViewer)
                // Pulls the rest (split completed, etc.) the Viewer does not carry.
                settingsState.refresh(force = true)
            } else if (previous != null) {
                settingsState.clearSynced()
            }
            lastSettingsViewerKey = settingsViewerKey
        }
    }

    // Sign-in modal auto-open: first anonymous launch only. Logout never
    // reopens it (the flag persists); login closes it via the viewer effect.
    val autoOpenSignIn = lastLoginError != null ||
        (session is SessionState.Anonymous && !store.getBoolean(SettingsStoreKeys.SeenSignIn, false))

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalImageQuality provides settingsState.imageQuality.resolved(unmeteredConnection),
        LocalNetworkThrottle provides dependencies.networkThrottle,
    ) {
        Theme(
            palette = settingsState.palette,
            type = settingsState.themeType,
            font = settingsState.font,
            surfaces = settingsState.surfaces,
            style = settingsState.themeStyle,
        ) {
            val current = session
            // No login wall: anonymous users enter the shell as guests
            // (Discover works without a session); sign-in is on-demand
            // via the modal wherever a gated surface needs it.
            val route = when (current) {
                SessionState.Loading -> AppRoute.LOADING
                SessionState.Anonymous -> AppRoute.SHELL
                is SessionState.Authenticated -> AppRoute.SHELL
                is SessionState.Error -> AppRoute.ERROR
            }
            // Same motion language as the tab switch: fade + quarter slide.
            // Opaque theme backdrop: this AnimatedContent sits outside every
            // Scaffold, so the crossfade alpha overlap would otherwise bleed
            // the (light) window background through. (Tab switches don't need
            // this: they compose inside Root's opaque Scaffold.)
            AnimatedContent(
                targetState = route,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTagsAsResourceIds(),
                transitionSpec = {
                    (fadeIn(animationSpec = tabFadeSpec()) +
                        slideInHorizontally(animationSpec = tabFadeSpec()) { it / 4 } togetherWith
                        fadeOut(animationSpec = tabFadeSpec()) +
                        slideOutHorizontally(animationSpec = tabFadeSpec()) { -it / 4 }) using
                        SizeTransform(clip = false)
                },
                label = "session-route",
            ) { activeRoute ->
                when (activeRoute) {
                    AppRoute.LOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = strings.authLoadingLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    AppRoute.SHELL -> {
                        Root(
                            settingsState = settingsState,
                            capabilities = capabilities,
                            viewer = (current as? SessionState.Authenticated)?.viewer,
                            onLogout = {
                                dependencies.logout()
                            },
                            animeListRepository = dependencies.animeListRepository,
                            mangaListRepository = dependencies.mangaListRepository,
                            mediaListEntryRepository = dependencies.mediaListEntryRepository,
                            mediaDetailRepository = dependencies.mediaDetailRepository,
                            animeThemesRepository = dependencies.animeThemesRepository,
                            characterDetailRepository = dependencies.characterDetailRepository,
                            staffDetailRepository = dependencies.staffDetailRepository,
                            studioDetailRepository = dependencies.studioDetailRepository,
                            exploreRepository = dependencies.exploreRepository,
                            calendarRepository = dependencies.calendarRepository,
                            dashboardRepository = dependencies.dashboardRepository,
                            profileRepository = dependencies.profileRepository,
                            favouriteRepository = dependencies.favouriteRepository,
                            notificationsRepository = dependencies.notificationsRepository,
                            mirrorSyncCoordinator = dependencies.mirrorSyncCoordinator,
                            urlOpener = dependencies.urlOpener,
                            shareLauncher = dependencies.shareLauncher,
                            sortPersistence = sortPersistence,
                            authServices = authServices,
                            loginErrorMessage = loginErrorMessage,
                            onSignInDismissed = { dependencies.sessionManager.clearLoginError() },
                            isAuthConfigured = isAuthConfigured,
                            accounts = accountsUiState,
                            mirrorOutbox = mirrorOutbox,
                            onConnectAccount = { dependencies.beginLink(it) },
                            onDisconnectAccount = { dependencies.disconnectAccount(it) },
                            onSetFollowed = { dependencies.setFollowed(it) },
                            onDismissLostAccess = { dependencies.dismissLostAccess() },
                            onSetAutoCopy = { dependencies.setAutoCopy(it) },
                            onRetryMirror = { dependencies.retryMirror() },
                            accountEvents = dependencies.accountEvents.events,
                            autoOpenSignIn = autoOpenSignIn,
                            onAutoSignInShown = {
                                store.putBoolean(SettingsStoreKeys.SeenSignIn, true)
                            },
                            deepLinkDestination = deepLinkDestination,
                            onDeepLinkHandled = { dependencies.consumeDeepLink() },
                        )
                    }
                    AppRoute.ERROR -> {
                        val sessionError = (current as? SessionState.Error)?.error
                        val detail = sessionError?.let { strings.networkErrorMessage(it) }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MessagePanel(
                                title = strings.errorUnknownLabel,
                                // Unknown maps to the same copy as the title; hiding
                                // it avoids showing the same line twice.
                                message = detail?.takeIf { it != strings.errorUnknownLabel },
                                icon = AppIcons.Warning,
                                actionLabel = strings.retryAction,
                                onAction = {
                                    scope.launch { dependencies.sessionManager.bootstrap() }
                                },
                            )
                            TextButton(
                                onClick = {
                                    dependencies.logout()
                                },
                            ) {
                                Text(
                                    text = strings.logoutAction,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Session routing key: drives the landing/shell transition, never the session itself. */
private enum class AppRoute {
    LOADING,
    SHELL,
    ERROR,
}
