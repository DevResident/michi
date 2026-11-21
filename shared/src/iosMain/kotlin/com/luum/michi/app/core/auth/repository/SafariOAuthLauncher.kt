package com.luum.michi.app.core.auth.repository

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import com.luum.michi.app.core.auth.domain.OAuthBrowser

/**
 * Opens a provider's OAuth authorize page in the system browser. After the user
 * authorizes, the provider redirects to `michi://oauth/callback`, which the iOS
 * app's URL scheme handler catches.
 *
 * `SFSafariViewController` would give a slightly better UX (in-app browser),
 * but it requires a `UIViewController` reference to present on. Plain
 * `openURL` works without that coupling.
 */
internal class SafariOAuthLauncher : OAuthBrowser {
    override fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}
