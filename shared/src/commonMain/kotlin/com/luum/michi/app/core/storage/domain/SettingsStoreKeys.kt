package com.luum.michi.app.core.storage.domain

/**
 * Store keys shared across the composition root. `App` (theme + language) and `Root`
 * (default discover tab) read these at startup, while [com.luum.michi.app.settings.ui.state.SettingsState]
 * writes them — so the strings must stay in one place.
 */
internal object SettingsStoreKeys {
    const val ThemeMode = "theme_mode"
    const val ThemePalette = "theme_palette"
    /** Global MaterialKolor palette style (TonalSpot, Vibrant, …). */
    const val ThemeStyle = "theme_style"
    const val Font = "app_font"
    const val Language = "app_language"
    /** Where the shell opens ("Pestaña al abrir") + its dependent sub-choice. */
    const val StartTab = "default_home_tab"
    const val StartAnimeSection = "start_anime_section"
    const val StartMangaSection = "start_manga_section"
    const val StartCatalogType = "start_catalog_type"
    /** Sign-in modal auto-opened once (first anonymous launch, never again). */
    const val SeenSignIn = "seen_sign_in"
    /** Sheet/chrome surface style (Settings toggle). */
    const val SurfaceStyle = "surface_style"
    /** Last resolved Viewer, for an instant shell on cold start. */
    const val CachedViewer = "cached_viewer"
    /** Which auth provider currently owns the session (AniList by default). */
    const val ActiveProvider = "active_auth_provider"
    /** The one linked account's viewer identity (a separate token lives in its own storage). */
    const val LinkedAccount = "linked_account"
    /** A linked token died (401/invalid_grant); shown until dismissed or relinked. */
    const val LinkedLostAccess = "linked_lost_access"
    /** Whether automatic copies run (default on when a link is created). */
    const val LinkedAutoCopy = "linked_auto_copy"
}
