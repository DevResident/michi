package com.luum.michi.app.core.language.domain

/**
 * One shipped version's in-app notes. Content is bundled with the app: the
 * What's new page never reads the network or the docs folder.
 */
data class ReleaseNotesVersion(
    val version: String,
    val sections: List<ReleaseNotesSection>,
)

data class ReleaseNotesSection(
    val title: String,
    val items: List<String>,
)
