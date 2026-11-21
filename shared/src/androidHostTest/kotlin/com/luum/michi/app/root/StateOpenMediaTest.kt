package com.luum.michi.app.root

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.navigation.domain.DetailDestination
import com.luum.michi.app.root.state.RootOverlay
import com.luum.michi.app.root.state.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A deep link can open a media detail while Settings is showing. The detail is
 * content-routed below Settings, so opening it must close Settings or the detail
 * stays hidden behind an empty-titled overlay.
 */
class StateOpenMediaTest {

    private fun state() = State()

    @Test
    fun openingMediaClosesSettingsAndStacksTheDetail() {
        val state = state()
        state.open(RootOverlay.Settings)
        assertTrue(state.isOpen(RootOverlay.Settings))

        state.openMedia(aniListRef(1))

        assertFalse(state.isOpen(RootOverlay.Settings), "the detail must not stay hidden behind Settings")
        assertTrue(state.isDetailOpen)
        assertEquals(DetailDestination.Media(aniListRef(1)), state.currentDetail)
    }

    @Test
    fun openingMediaWithSettingsClosedIsUnaffected() {
        val state = state()

        state.openMedia(aniListRef(7))

        assertFalse(state.isOpen(RootOverlay.Settings))
        assertEquals(DetailDestination.Media(aniListRef(7)), state.currentDetail)
    }

    @Test
    fun aTypeHintDoesNotDuplicateTheSameMediaOnTop() {
        val state = state()

        state.openMedia(aniListRef(3))
        state.openMedia(aniListRef(3), MediaType.MANGA)

        assertEquals(DetailDestination.Media(aniListRef(3)), state.currentDetail)
        state.closeDetail()
        assertFalse(state.isDetailOpen, "only one entry was stacked")
    }
}
