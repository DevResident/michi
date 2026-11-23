package com.luum.michi.app.mirror.ui.model

import com.luum.michi.app.core.medialist.domain.MirrorDiff

/** The three lists a comparison can show, in the order the tabs render. */
internal enum class MirrorSection { UPDATE, ADD, LINKED_ONLY }

/** One visible tab: a section and the rows it currently has. */
internal data class MirrorTab(
    val section: MirrorSection,
    val count: Int,
)

/**
 * Tabs only exist for sections with rows, and always in the agreed order:
 * Update · Add · Only in the other account. Pure, so the visible set and the
 * initial fallback are testable without composition.
 */
internal fun mirrorTabs(diff: MirrorDiff): List<MirrorTab> = buildList {
    if (diff.updates.isNotEmpty()) {
        add(MirrorTab(MirrorSection.UPDATE, diff.updates.size))
    }
    if (diff.additions.isNotEmpty()) {
        add(MirrorTab(MirrorSection.ADD, diff.additions.size))
    }
    if (diff.linkedOnly.isNotEmpty()) {
        add(MirrorTab(MirrorSection.LINKED_ONLY, diff.linkedOnly.size))
    }
}

/** Initial tab: the first section that actually has rows. */
internal fun initialMirrorSection(diff: MirrorDiff): MirrorSection? =
    mirrorTabs(diff).firstOrNull()?.section
