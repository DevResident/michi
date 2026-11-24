package com.luum.michi.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

@Composable
internal actual fun rememberClipboardWriter(): (String) -> Unit = remember {
    { text -> UIPasteboard.generalPasteboard.string = text }
}
