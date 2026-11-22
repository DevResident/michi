package com.luum.michi.app

import com.luum.michi.app.core.favourite.domain.FavouriteRepository
import com.luum.michi.app.core.favourite.repository.FavouriteRepositoryImpl
import com.luum.michi.app.profile.repository.ProfileRepositoryImpl
import com.luum.michi.app.profile.repository.ProfileDiskCache
import com.luum.michi.app.profile.domain.ProfileRepository
import com.luum.michi.app.mediaList.repository.anime.AnimeListRepositoryImpl
import com.luum.michi.app.mediaList.domain.anime.AnimeListRepository
import com.luum.michi.app.mediaDetail.repository.mal.ProviderAwareMediaDetailRepository
import com.luum.michi.app.mediaList.repository.ProviderRoutingAnimeListRepository
import com.luum.michi.app.mediaList.repository.ProviderRoutingMangaListRepository
import com.luum.michi.app.mediaList.repository.ProviderRoutingMediaListEntryRepository
import com.luum.michi.app.mediaList.repository.mal.MalAnimeListRepository
import com.luum.michi.app.mediaList.repository.mal.MalHydrationStore
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import com.luum.michi.app.mediaList.repository.mal.MalMangaListRepository
import com.luum.michi.app.mediaList.repository.mal.MalMediaListEntryRepository
import com.luum.michi.app.calendar.repository.CalendarRepositoryImpl
import com.luum.michi.app.calendar.domain.CalendarRepository
import com.luum.michi.app.mediaDetail.repository.character.CharacterDetailRepositoryImpl
import com.luum.michi.app.mediaDetail.domain.character.CharacterDetailRepository
import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.OAuthBrowser
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.repository.AniListAuthProvider
import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.mal.repository.MalAuthProvider
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.mal.repository.SettingsStorePendingMalAuthStore
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridgeImpl
import com.luum.michi.app.core.platform.domain.UrlOpener
import com.luum.michi.app.core.platform.domain.ShareLauncher
import com.luum.michi.app.core.navigation.domain.DetailDestination
import com.luum.michi.app.core.navigation.domain.parseAniListDestination
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.network.domain.AniListGraphQLClient
import com.luum.michi.app.core.network.domain.NetworkThrottle
import com.luum.michi.app.core.network.repository.AniListRateLimiter
import com.luum.michi.app.core.network.repository.KtorAniListGraphQLClient
import com.luum.michi.app.core.network.repository.createAniListHttpClient
import com.luum.michi.app.core.session.domain.AniListViewerRepository
import com.luum.michi.app.core.session.repository.AniListViewerRepositoryImpl
import com.luum.michi.app.core.session.domain.SessionManager
import com.luum.michi.app.core.session.repository.SettingsStoreActiveProviderStore
import com.luum.michi.app.core.session.repository.SettingsStoreCachedViewerStore
import com.luum.michi.app.core.storage.domain.SettingsStore
import com.luum.michi.app.core.storage.domain.FileStore
import com.luum.michi.app.mediaList.repository.MediaListDiskCache
import com.luum.michi.app.core.platform.domain.AlwaysUnmeteredConnectivity
import com.luum.michi.app.core.platform.domain.Connectivity
import com.luum.michi.app.discover.repository.DashboardRepositoryImpl
import com.luum.michi.app.discover.repository.ExploreRepositoryImpl
import com.luum.michi.app.discover.domain.DashboardRepository
import com.luum.michi.app.discover.domain.ExploreRepository
import com.luum.michi.app.mediaList.repository.manga.MangaListRepositoryImpl
import com.luum.michi.app.mediaList.domain.manga.MangaListRepository
import com.luum.michi.app.mediaDetail.repository.media.MediaDetailRepositoryImpl
import com.luum.michi.app.mediaDetail.repository.media.AnimeThemesRepositoryImpl
import com.luum.michi.app.core.medialist.repository.MediaListEntryRepositoryImpl
import com.luum.michi.app.mediaDetail.domain.media.MediaDetailRepository
import com.luum.michi.app.mediaDetail.domain.media.AnimeThemesRepository
import com.luum.michi.app.notifications.repository.NotificationsRepositoryImpl
import com.luum.michi.app.notifications.domain.NotificationsRepository
import com.luum.michi.app.settings.repository.SettingsRepositoryImpl
import com.luum.michi.app.settings.domain.SettingsRepository
import com.luum.michi.app.mediaDetail.repository.staff.StaffDetailRepositoryImpl
import com.luum.michi.app.mediaDetail.domain.staff.StaffDetailRepository
import com.luum.michi.app.mediaDetail.repository.studio.StudioDetailRepositoryImpl
import com.luum.michi.app.mediaDetail.domain.studio.StudioDetailRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.luum.michi.app.discover.repository.clearLegacyDashboardCache
import com.luum.michi.app.mediaDetail.domain.common.ExternalLinkCatalogRepository
import com.luum.michi.app.mediaDetail.repository.ExternalLinkCatalogRepositoryImpl

/**
 * App-level composition root. Lives exactly once per process: owned by
 * `MichiApplication` on Android and by the `IosMichiDependencies` holder on
 * iOS, which provide the platform-specific token storages (one per provider)
 * and the OAuth browser. Never construct it from a recreated UI entry
 * (Activity / view controller), or each recreation would leak a CoroutineScope
 * + HttpClient.
 *
 * The Android entry must also dispatch deep links (`michi://oauth/callback`) to
 * [onOAuthCallback]; iOS does the same from its `onOpenURL` handler.
 */
class MichiDependencies internal constructor(
    private val tokenStorages: Map<ProviderId, TokenStorage>,
    private val oAuthBrowser: OAuthBrowser,
    internal val urlOpener: UrlOpener,
    internal val shareLauncher: ShareLauncher,
    internal val settingsStore: SettingsStore,
    /** Cache-sized files (saved lists); see [FileStore]. */
    internal val fileStore: FileStore,
    /** Network metering for AUTO image quality; platform impls override it. */
    internal val connectivity: Connectivity = AlwaysUnmeteredConnectivity(),
    /** Debug builds log GraphQL timing and MAL's unmatched-id counts. */
    internal val isDebug: Boolean = false,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal val httpClient: HttpClient = createAniListHttpClient()

    /**
     * One rate limiter for the whole process: the client sends through it and
     * [networkThrottle] exposes its wait state to the UI. Sharing the instance
     * means a single window/semaphore, and a single honest "the brake is on".
     */
    private val rateLimiter: AniListRateLimiter = AniListRateLimiter()

    /** Read-only wait state of [rateLimiter], for the UI notice. */
    internal val networkThrottle: NetworkThrottle = rateLimiter

    internal val graphQLClient: AniListGraphQLClient = KtorAniListGraphQLClient(
        httpClient = httpClient,
        // Never send an expired token; `validToken` refreshes when possible and
        // closes the session when a token is dead beyond recovery. Scoped to
        // AniList so a MAL session never leaks its token to the GraphQL API.
        tokenProvider = { sessionManager.validToken(ProviderId.AniList) },
        debugLogging = isDebug,
        rateLimiter = rateLimiter,
        // A mid-session 401 closes the session only if that token is still the
        // stored one (a stale 401 never closes a newer session).
        onUnauthorized = { token -> scope.launch { sessionManager.onUnauthorized(token) } },
    )
    internal val viewerRepository: AniListViewerRepository =
        AniListViewerRepositoryImpl(graphQLClient)

    /** One MAL client for login and lists: a single conservative 60/min limiter. */
    private val malHttp: MalHttp = MalHttp(httpClient)

    /**
     * Login services exposed to the sign-in modal. AniList is always present;
     * MAL is appended when `malClientId` is configured (release included).
     */
    internal val authProviders: List<AuthProvider> = buildAuthProviders(
        isMalConfigured = MalOAuthConfig.isConfigured,
        aniList = { AniListAuthProvider(oAuthBrowser, viewerRepository) },
        mal = {
            MalAuthProvider(
                browser = oAuthBrowser,
                http = malHttp,
                pendingStore = SettingsStorePendingMalAuthStore(settingsStore),
            )
        },
    )

    /** The active account's capabilities; AniList when the provider is unknown. */
    internal fun capabilitiesFor(provider: ProviderId): ProviderCapabilities =
        authProviders.firstOrNull { it.id == provider }?.capabilities
            ?: ProviderCapabilities.AniList

    /** One cache instance serves both list repositories and the session wipe. */
    private val mediaListCache: MediaListDiskCache = MediaListDiskCache(fileStore)

    /** The viewer profile's disk cache, wiped with the lists on logout/401. */
    private val profileCache: ProfileDiskCache = ProfileDiskCache(fileStore)

    /** MAL ids -> AniList ids, persisted for writes and incremental lists. */
    private val mediaIdBridge: MediaIdBridgeImpl =
        MediaIdBridgeImpl(graphQLClient, fileStore)

    /** Shared MAL entry read/write gateway (raw dates + 401 handling). */
    private val malEntryClient: MalEntryClient = MalEntryClient(
        http = malHttp,
        bridge = mediaIdBridge,
        tokenProvider = { sessionManager.validToken(ProviderId.Mal) },
        onUnauthorized = { token -> sessionManager.onUnauthorized(token) },
    )

    private val malListLoader: MalListLoader = MalListLoader(
        http = malHttp,
        tokenProvider = { sessionManager.validToken(ProviderId.Mal) },
        bridge = mediaIdBridge,
        cache = mediaListCache,
        hydrationStore = MalHydrationStore(fileStore),
        onUnauthorized = { token -> sessionManager.onUnauthorized(token) },
        onUnmatched = { type, ids ->
            if (isDebug) println("[Michi] MAL ${type.name} list: ${ids.size} unmatched $ids")
        },
    )

    private val aniListAnimeListRepository: AnimeListRepository =
        AnimeListRepositoryImpl(graphQLClient, mediaListCache)

    private val aniListMangaListRepository: MangaListRepository =
        MangaListRepositoryImpl(graphQLClient, mediaListCache)

    private val malAnimeListRepository: AnimeListRepository =
        MalAnimeListRepository(malListLoader)

    private val malMangaListRepository: MangaListRepository =
        MalMangaListRepository(malListLoader)

    internal val animeListRepository: AnimeListRepository =
        ProviderRoutingAnimeListRepository(
            activeProvider = { sessionManager.activeProvider() },
            aniList = aniListAnimeListRepository,
            mal = malAnimeListRepository,
        )

    internal val mangaListRepository: MangaListRepository =
        ProviderRoutingMangaListRepository(
            activeProvider = { sessionManager.activeProvider() },
            aniList = aniListMangaListRepository,
            mal = malMangaListRepository,
        )

    internal val profileRepository: ProfileRepository =
        ProfileRepositoryImpl(graphQLClient, profileCache)

    internal val externalLinkCatalogRepository: ExternalLinkCatalogRepository =
        ExternalLinkCatalogRepositoryImpl(graphQLClient, settingsStore, scope)

    internal val dashboardRepository: DashboardRepository =
        DashboardRepositoryImpl(graphQLClient)

    internal val exploreRepository: ExploreRepository =
        ExploreRepositoryImpl(graphQLClient)

    internal val calendarRepository: CalendarRepository =
        CalendarRepositoryImpl(graphQLClient)

    private val aniListMediaDetailRepository: MediaDetailRepository =
        MediaDetailRepositoryImpl(graphQLClient)

    internal val mediaDetailRepository: MediaDetailRepository =
        ProviderAwareMediaDetailRepository(
            activeProvider = { sessionManager.activeProvider() },
            aniList = aniListMediaDetailRepository,
            malEntries = malEntryClient,
        )

    internal val animeThemesRepository: AnimeThemesRepository =
        AnimeThemesRepositoryImpl(httpClient)

    internal val favouriteRepository: FavouriteRepository =
        FavouriteRepositoryImpl(graphQLClient)

    private val aniListMediaListEntryRepository: MediaListEntryRepository =
        MediaListEntryRepositoryImpl(graphQLClient, favouriteRepository)

    private val malMediaListEntryRepository: MediaListEntryRepository =
        MalMediaListEntryRepository(mediaIdBridge, malEntryClient)

    internal val mediaListEntryRepository: MediaListEntryRepository =
        ProviderRoutingMediaListEntryRepository(
            activeProvider = { sessionManager.activeProvider() },
            aniList = aniListMediaListEntryRepository,
            mal = malMediaListEntryRepository,
        )

    internal val notificationsRepository: NotificationsRepository =
        NotificationsRepositoryImpl(graphQLClient)

    internal val studioDetailRepository: StudioDetailRepository =
        StudioDetailRepositoryImpl(graphQLClient, favouriteRepository)

    internal val characterDetailRepository: CharacterDetailRepository =
        CharacterDetailRepositoryImpl(graphQLClient, externalLinkCatalogRepository, favouriteRepository)

    internal val staffDetailRepository: StaffDetailRepository =
        StaffDetailRepositoryImpl(graphQLClient, externalLinkCatalogRepository, favouriteRepository)

    internal val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(graphQLClient)

    internal val sessionManager: SessionManager =
        SessionManager(
            providers = authProviders,
            tokenStorages = tokenStorages,
            activeProviderStore = SettingsStoreActiveProviderStore(settingsStore),
            cachedViewerStore = SettingsStoreCachedViewerStore(settingsStore),
            onClearUserData = {
                // Both caches live in the same FileStore/folder; clear both for
                // an explicit, best-effort privacy wipe on logout/401/expired token.
                mediaListCache.clear()
                profileCache.clear()
            },
        )

    /**
     * Bootstraps the app: clears the removed dashboard disk cache, then checks
     * the persisted token and fetches the viewer if present.
     * Called once at app start by the platform entry.
     */
    fun bootstrap() {
        clearLegacyDashboardCache(settingsStore)
        scope.launch { sessionManager.bootstrap() }
    }

    /**
     * Called by the platform when an OAuth deep link fires. The provider that
     * recognizes the URI stores the token and refreshes the session; an
     * unknown URI is a no-op.
     */
    fun onOAuthCallback(uri: String) {
        scope.launch { sessionManager.onOAuthCallback(uri) }
    }

    /**
     * Deep-linked AniList destination from a web link, or null. The UI collects
     * this and opens the detail page, then calls [consumeDeepLink].
     */
    private val _pendingDeepLink = MutableStateFlow<DetailDestination?>(null)
    internal val pendingDeepLink: StateFlow<DetailDestination?> = _pendingDeepLink.asStateFlow()

    /** Called by the platform when an AniList entity link is opened. No-op otherwise. */
    fun onDeepLink(url: String) {
        parseAniListDestination(url)?.let { _pendingDeepLink.value = it }
    }

    fun consumeDeepLink() {
        _pendingDeepLink.value = null
    }

    fun logout() {
        scope.launch { sessionManager.logout() }
    }
}

/**
 * AniList is always registered; MAL is appended when a `malClientId` exists.
 * Without the optional MAL configuration the list stays exactly `[AniList]`.
 */
internal fun buildAuthProviders(
    isMalConfigured: Boolean,
    aniList: () -> AuthProvider,
    mal: () -> AuthProvider,
): List<AuthProvider> = buildList {
    add(aniList())
    if (isMalConfigured) add(mal())
}
