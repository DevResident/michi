package com.luum.michi.app.ui.components

import androidx.compose.runtime.Composable

/**
 * Returns a platform writer the caller invokes from a click lambda. Android uses
 * `ClipboardManager`; iOS uses `UIPasteboard`. Kept as a composable factory so
 * the platform implementations can capture their context/state once.
 */
@Composable
internal expect fun rememberClipboardWriter(): (String) -> Unit
