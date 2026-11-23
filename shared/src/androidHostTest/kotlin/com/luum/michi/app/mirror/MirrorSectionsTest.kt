package com.luum.michi.app.mirror

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorLinkedOnlyRow
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.MirrorPreviewValues
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.mirror.ui.model.MirrorSection
import com.luum.michi.app.mirror.ui.model.initialMirrorSection
import com.luum.michi.app.mirror.ui.model.mirrorTabs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun values(progress: Int = 12) = MirrorPreviewValues(
    status = MediaListStatus.CURRENT,
    progress = progress,
    progressVolumes = null,
    score = 9f,
    priority = 2,
    repeat = 0,
    notes = "note",
    startedAt = null,
    completedAt = null,
)

private fun row(key: String) = MirrorPreviewRow(
    key = key,
    media = MirrorMediaSummary(
        aniListId = 10,
        malId = 20,
        type = MediaType.ANIME,
        title = "Test",
    ),
    primary = MirrorEntry(
        sourceProvider = ProviderId.AniList,
        aniListId = 10,
        malId = 20,
        type = MediaType.ANIME,
        status = MediaListStatus.CURRENT,
        progress = 12,
        progressVolumes = null,
        score = 9f,
        scoreFormat = ScoreFormat.POINT_10_DECIMAL,
        repeat = 0,
        priority = 2,
        notes = "note",
        isPrivate = false,
        isRewatching = false,
        startedAt = null,
        completedAt = null,
    ),
    before = values(11),
    after = values(12),
    changedFields = setOf(MirrorField.PROGRESS),
    newerInLinked = false,
)

private fun linkedRow(key: Int) = MirrorLinkedOnlyRow(
    media = MirrorMediaSummary(
        aniListId = key,
        malId = key,
        type = MediaType.ANIME,
        title = "Linked $key",
    ),
    values = values(),
)

private fun diff(
    updates: List<MirrorPreviewRow> = emptyList(),
    additions: List<MirrorPreviewRow> = emptyList(),
    linkedOnly: List<MirrorLinkedOnlyRow> = emptyList(),
) = MirrorDiff(
    targetProvider = ProviderId.Mal,
    targetScoreFormat = ScoreFormat.POINT_10,
    additions = additions,
    updates = updates,
    linkedOnly = linkedOnly,
    noEquivalent = 0,
    privateOmitted = 0,
)

class MirrorSectionsTest {

    @Test
    fun tabsFollowTheAgreedOrderAndSkipEmptySections() {
        val tabs = mirrorTabs(
            diff(
                updates = listOf(row("update")),
                additions = listOf(row("add")),
                linkedOnly = listOf(linkedRow(1)),
            ),
        )

        assertEquals(
            listOf(MirrorSection.UPDATE, MirrorSection.ADD, MirrorSection.LINKED_ONLY),
            tabs.map { it.section },
        )
        assertEquals(listOf(1, 1, 1), tabs.map { it.count })
    }

    @Test
    fun onlySectionsWithRowsAreVisible() {
        assertEquals(
            listOf(MirrorSection.ADD),
            mirrorTabs(diff(additions = listOf(row("add")))).map { it.section },
        )
        assertEquals(
            listOf(MirrorSection.LINKED_ONLY),
            mirrorTabs(diff(linkedOnly = listOf(linkedRow(1)))).map { it.section },
        )
    }

    @Test
    fun theInitialTabIsTheFirstOneWithRows() {
        assertEquals(
            MirrorSection.UPDATE,
            initialMirrorSection(
                diff(
                    updates = listOf(row("update")),
                    additions = listOf(row("add")),
                ),
            ),
        )
        assertEquals(
            MirrorSection.ADD,
            initialMirrorSection(diff(additions = listOf(row("add")))),
        )
        assertNull(initialMirrorSection(diff()))
    }
}
