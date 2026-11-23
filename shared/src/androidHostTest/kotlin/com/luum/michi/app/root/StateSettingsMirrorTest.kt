package com.luum.michi.app.root

import com.luum.michi.app.root.state.BackStep
import com.luum.michi.app.root.state.RootOverlay
import com.luum.michi.app.root.state.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compare lists is a page inside Settings, opened over Accounts. */
class StateSettingsMirrorTest {

    private fun openMirror(): State = State().apply {
        open(RootOverlay.Settings)
        openSettingsAccounts()
        openSettingsMirror()
    }

    @Test
    fun mirrorOpensOverAccountsAndBackReturnsToIt() {
        val state = openMirror()

        assertTrue(state.settingsMirrorOpen)
        assertTrue(state.settingsAccountsOpen)
        assertEquals(BackStep.CloseSettingsMirror, state.nextBackStep())

        assertTrue(state.applyBackStep())
        assertFalse(state.settingsMirrorOpen)
        assertTrue(state.settingsAccountsOpen, "Accounts stays underneath")

        assertEquals(BackStep.CloseSettingsAccounts, state.nextBackStep())
    }

    @Test
    fun openingAccountsClosesAStaleMirrorPage() {
        val state = openMirror()

        state.openSettingsAccounts()

        assertFalse(state.settingsMirrorOpen)
        assertTrue(state.settingsAccountsOpen)
    }

    @Test
    fun resetClosesTheMirrorPage() {
        val state = openMirror()

        state.reset()

        assertFalse(state.settingsMirrorOpen)
        assertFalse(state.settingsAccountsOpen)
    }
}
