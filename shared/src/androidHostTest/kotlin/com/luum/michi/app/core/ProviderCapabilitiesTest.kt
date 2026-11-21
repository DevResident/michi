package com.luum.michi.app.core

import com.luum.michi.app.MinimalProviderCapabilities
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.editableStatuses
import com.luum.michi.app.core.auth.domain.effectiveScoreFormat
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseScoreFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCapabilitiesTest {

    @Test
    fun anilistSupportsEveryAccountFeature() {
        val caps = ProviderCapabilities.AniList

        assertNull(caps.fixedScoreFormat)
        assertTrue(caps.advancedScores)
        assertTrue(caps.privateEntries)
        assertTrue(caps.hiddenFromStatusLists)
        assertTrue(caps.repeatCount)
        assertTrue(caps.priority)
        assertTrue(caps.notes)
        assertTrue(caps.repeatingStatus)
        assertTrue(caps.favourites)
        assertTrue(caps.notifications)
        assertTrue(caps.social)
        assertTrue(caps.profileStatistics)
        assertTrue(caps.remoteSettings)
    }

    @Test
    fun malFixesTheScoreAndHasNoAccountExtras() {
        val caps = ProviderCapabilities.Mal

        assertEquals(ScoreFormat.POINT_10, caps.fixedScoreFormat)
        assertTrue(caps.repeatCount)
        assertTrue(caps.priority)
        assertTrue(caps.notes)
        assertFalse(caps.advancedScores)
        assertFalse(caps.privateEntries)
        assertFalse(caps.hiddenFromStatusLists)
        assertFalse(caps.repeatingStatus)
        assertFalse(caps.favourites)
        assertFalse(caps.notifications)
        assertFalse(caps.social)
        assertFalse(caps.profileStatistics)
        assertFalse(caps.remoteSettings)
    }

    @Test
    fun malEditorDropsOnlyRepeating() {
        assertEquals(
            listOf(
                MediaListStatus.CURRENT,
                MediaListStatus.PLANNING,
                MediaListStatus.COMPLETED,
                MediaListStatus.DROPPED,
                MediaListStatus.PAUSED,
            ),
            ProviderCapabilities.Mal.editableStatuses(),
        )
    }

    @Test
    fun aNullFixedFormatKeepsTheUserFormat() {
        assertEquals(
            ScoreFormat.POINT_100,
            ProviderCapabilities.AniList.effectiveScoreFormat(ScoreFormat.POINT_100),
        )
    }

    @Test
    fun aNullViewerFormatKeepsTheExistingFallback() {
        assertEquals(
            ScoreFormat.POINT_10_DECIMAL,
            ProviderCapabilities.AniList.effectiveScoreFormat(parseScoreFormat(null)),
        )
    }

    @Test
    fun aFixedFormatWinsOverTheUserFormat() {
        val caps = MinimalProviderCapabilities.copy(fixedScoreFormat = ScoreFormat.POINT_10)

        assertEquals(
            ScoreFormat.POINT_10,
            caps.effectiveScoreFormat(ScoreFormat.POINT_100),
        )
    }

    @Test
    fun anilistKeepsEveryStatusInTheEditorOrder() {
        assertEquals(
            MediaListStatus.entries,
            ProviderCapabilities.AniList.editableStatuses(),
        )
    }

    @Test
    fun withoutRepeatingStatusTheOrderStaysStable() {
        assertEquals(
            listOf(
                MediaListStatus.CURRENT,
                MediaListStatus.PLANNING,
                MediaListStatus.COMPLETED,
                MediaListStatus.DROPPED,
                MediaListStatus.PAUSED,
            ),
            MinimalProviderCapabilities.editableStatuses(),
        )
    }
}
