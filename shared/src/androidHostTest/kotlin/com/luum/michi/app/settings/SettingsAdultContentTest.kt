package com.luum.michi.app.settings

import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.settings.ui.state.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun settings(
    store: FakeSettingsStore,
    canSync: Boolean,
    useLocalAdultContent: Boolean,
): SettingsState = SettingsState(
    repository = FakeSettingsRepository(),
    store = store,
    scope = CoroutineScope(Dispatchers.Unconfined),
    canSync = canSync,
    useLocalAdultContent = useLocalAdultContent,
)

class SettingsAdultContentTest {

    @Test
    fun malKeepsEighteenPlusAsALocalPreference() {
        val store = FakeSettingsStore()
        val state = settings(store, canSync = false, useLocalAdultContent = true)

        state.displayAdultContent = true
        state.seedFrom(Viewer(id = 1, name = "mal", displayAdultContent = false))

        assertTrue(state.displayAdultContent, "seedFrom must not touch the local value")
        val recreated = settings(store, canSync = false, useLocalAdultContent = true)
        assertTrue(recreated.displayAdultContent, "the local value persists across holders")
    }

    @Test
    fun signingOutOfMalResetsTheLocalEighteenPlus() {
        val store = FakeSettingsStore()
        val state = settings(store, canSync = false, useLocalAdultContent = true)
        state.displayAdultContent = true

        state.clearSynced()

        assertFalse(state.displayAdultContent)
        val nextAccount = settings(store, canSync = false, useLocalAdultContent = true)
        assertFalse(nextAccount.displayAdultContent, "the next MAL account starts with 18+ off")
    }

    @Test
    fun anilistStillUsesTheSyncedValue() {
        val store = FakeSettingsStore()
        val state = settings(store, canSync = true, useLocalAdultContent = false)

        state.displayAdultContent = true
        state.seedFrom(Viewer(id = 1, name = "anilist", displayAdultContent = false))
        assertFalse(state.displayAdultContent, "AniList seeds 18+ from the viewer")

        state.displayAdultContent = true
        state.clearSynced()
        assertFalse(state.displayAdultContent, "AniList resets 18+ with the synced prefs")
    }

    @Test
    fun aGuestStaysOffEvenAfterALocalMalPreference() {
        val store = FakeSettingsStore()
        settings(store, canSync = false, useLocalAdultContent = true).displayAdultContent = true

        val guest = settings(store, canSync = false, useLocalAdultContent = false)
        guest.displayAdultContent = true

        assertFalse(guest.displayAdultContent, "guests never see or enable 18+")
    }
}
