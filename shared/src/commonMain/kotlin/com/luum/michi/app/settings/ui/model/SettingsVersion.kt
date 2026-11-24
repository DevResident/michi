package com.luum.michi.app.settings.ui.model

/** The exact text the Version row shows and copies. */
internal fun versionLabel(version: String, versionCode: Int): String =
    "${baseVersion(version)} ($versionCode)"

/**
 * The short version shown in the index and as the What's new heading. A dev
 * build's `+22.a403499` suffix is dropped; `1.1.0` becomes `1.1`.
 */
internal fun shortVersion(version: String): String =
    baseVersion(version).split('.').take(2).joinToString(".")

/** A dev build's `+27.<sha>` / `-unknown` suffix is not part of the version. */
internal fun baseVersion(version: String): String =
    version.substringBefore('+').substringBefore('-')
