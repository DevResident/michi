package com.luum.michi.app.core.platform.repository

import com.luum.michi.app.core.platform.domain.UrlOpener
import com.luum.michi.app.core.util.isWebUrl
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Plain browser opener, same contract as AndroidUrlOpener: web URLs open in
 * the system browser, anything else never leaves the app. Uses
 * `open(_:options:completionHandler:)`: iOS forces the deprecated
 * `openURL(_:)` to return false without opening anything.
 */
internal class IosUrlOpener : UrlOpener {
    override fun open(url: String) {
        if (!isWebUrl(url)) return
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
