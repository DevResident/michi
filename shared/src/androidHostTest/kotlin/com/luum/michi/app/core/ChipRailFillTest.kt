package com.luum.michi.app.core

import androidx.compose.ui.unit.dp
import com.luum.michi.app.ui.components.ChipTab
import com.luum.michi.app.ui.components.chipRailFits
import com.luum.michi.app.ui.components.chipRailUsesFill
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChipRailFillTest {

    @Test
    fun widestChipTimesCountPlusGapsAndPaddingMustFit() {
        assertTrue(
            chipRailFits(
                availableWidth = 360.dp,
                chipWidths = listOf(100.dp, 80.dp, 60.dp),
                spacing = 8.dp,
                horizontalPadding = 16.dp,
            ),
        )
    }

    @Test
    fun exactFitStillUsesTheFixedRow() {
        // 100 * 2 + 8 + 16 * 2 = 240
        assertTrue(
            chipRailFits(
                availableWidth = 240.dp,
                chipWidths = listOf(100.dp, 70.dp),
                spacing = 8.dp,
                horizontalPadding = 16.dp,
            ),
        )
    }

    @Test
    fun oneDpShortFallsBackToTheScrollingRail() {
        assertFalse(
            chipRailFits(
                availableWidth = 239.dp,
                chipWidths = listOf(100.dp, 70.dp),
                spacing = 8.dp,
                horizontalPadding = 16.dp,
            ),
        )
    }

    @Test
    fun anEmptyRailNeverTakesTheFixedRow() {
        assertFalse(
            chipRailFits(
                availableWidth = 320.dp,
                chipWidths = emptyList(),
            ),
        )
    }

    @Test
    fun tabsWithRemoteIconsNeverUseTheFixedRow() {
        val plain = listOf(
            ChipTab(value = "anime", label = "Anime"),
            ChipTab(value = "manga", label = "Manga"),
        )
        val withIcon = listOf(
            ChipTab(value = "anime", label = "Anime", iconUrl = "https://example.com/icon.png"),
            ChipTab(value = "manga", label = "Manga"),
        )

        assertTrue(chipRailUsesFill(fill = true, interactive = true, tabs = plain))
        assertFalse(chipRailUsesFill(fill = true, interactive = true, tabs = withIcon))
    }
}
