package com.luum.michi.app.core.auth.domain

/**
 * Opens a provider's OAuth authorize URL in a browser-like surface (Chrome
 * Custom Tabs on Android, SFSafariViewController/ASWebAuthenticationSession on
 * iOS).
 *
 * The flow does NOT return synchronously — the provider redirects the user
 * back to the app via the `michi://oauth/callback` deep link. The MainActivity
 * (Android) / SceneDelegate (iOS) is responsible for parsing the callback URI
 * and notifying `SessionManager`.
 */
internal interface OAuthBrowser {
    fun open(url: String)
}
