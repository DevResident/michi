package com.luum.michi.app.core.auth.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.luum.michi.app.core.auth.domain.OAuthBrowser

/**
 * Opens a provider's OAuth authorize page in a Chrome Custom Tab. After the user
 * authorizes, the provider redirects to `michi://oauth/callback`, which
 * `MainActivity`'s intent-filter catches.
 */
internal class ChromeCustomTabsOAuthLauncher(
    private val context: Context,
) : OAuthBrowser {

    override fun open(url: String) {
        val intent = CustomTabsIntent.Builder().build()
        intent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.launchUrl(context, Uri.parse(url))
    }
}
