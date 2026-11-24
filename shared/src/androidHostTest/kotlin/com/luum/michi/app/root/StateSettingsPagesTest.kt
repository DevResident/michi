package com.luum.michi.app.root

import com.luum.michi.app.root.state.BackStep
import com.luum.michi.app.root.state.RootOverlay
import com.luum.michi.app.root.state.State
import com.luum.michi.app.settings.ui.model.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every preference page is a page inside the Settings overlay, with its own back step. */
class StateSettingsPagesTest {

    private fun openPage(page: SettingsPage): State = State().apply {
        open(RootOverlay.Settings)
        openSettingsPage(page)
    }

    @Test
    fun backLeavesAWholeSettingsPageBeforeClosingSettings() {
        listOf(
            SettingsPage.General,
            SettingsPage.Appearance,
            SettingsPage.MyLists,
            SettingsPage.Notifications,
            SettingsPage.WhatsNew,
        ).forEach { page ->
            val state = openPage(page)

            assertEquals(page, state.settingsPage)
            assertEquals(BackStep.CloseSettingsPage, state.nextBackStep(), "$page back step")
            assertTrue(state.applyBackStep())
            assertEquals(SettingsPage.Main, state.settingsPage)
            assertTrue(state.isOpen(RootOverlay.Settings), "$page: Settings stays open")
            assertEquals(BackStep.CloseOverlay, state.nextBackStep())
        }
    }

    @Test
    fun openingSettingsAlwaysStartsOnTheIndex() {
        val state = openPage(SettingsPage.General)

        state.close(RootOverlay.Settings)
        state.open(RootOverlay.Settings)

        assertEquals(SettingsPage.Main, state.settingsPage)
    }

    @Test
    fun resetClosesEverySettingsPage() {
        val state = openPage(SettingsPage.WhatsNew)

        state.reset()

        assertEquals(SettingsPage.Main, state.settingsPage)
        assertFalse(state.settingsAboutOpen)
        assertFalse(state.settingsAccountsOpen)
        assertFalse(state.settingsMirrorOpen)
    }

    @Test
    fun signInClosesBeforeTheSettingsPage() {
        val state = openPage(SettingsPage.General)
        state.open(RootOverlay.SignIn)

        assertEquals(BackStep.CloseOverlay, state.nextBackStep())
        assertTrue(state.applyBackStep())
        assertEquals(SettingsPage.General, state.settingsPage, "the page is still underneath")
    }
}
