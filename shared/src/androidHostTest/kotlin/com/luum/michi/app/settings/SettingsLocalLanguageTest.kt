package com.luum.michi.app.settings

import com.luum.michi.app.settings.domain.model.StaffNameLanguage
import com.luum.michi.app.settings.domain.model.TitleLanguage
import com.luum.michi.app.settings.ui.state.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

private fun settings(store: FakeSettingsStore, mal: Boolean): SettingsState = SettingsState(
    repository = FakeSettingsRepository(),
    store = store,
    scope = CoroutineScope(Dispatchers.Unconfined),
    canSync = !mal,
    useLocalAdultContent = mal,
)

/** With MAL the title/staff-name languages are local prefs, like the 18+ gate. */
class SettingsLocalLanguageTest {

    @Test
    fun malKeepsTheLanguagesLocallyAndReLocalizesAtOnce() {
        val store = FakeSettingsStore()
        val mal = settings(store, mal = true)
        val before = mal.syncedGeneration

        mal.titleLanguage = TitleLanguage.ENGLISH
        mal.staffNameLanguage = StaffNameLanguage.NATIVE

        assertEquals(TitleLanguage.ENGLISH, mal.titleLanguage)
        assertEquals(StaffNameLanguage.NATIVE, mal.staffNameLanguage)
        assertEquals(before + 2, mal.syncedGeneration, "a local change has no save to wait for")

        mal.titleLanguage = TitleLanguage.ENGLISH
        assertEquals(before + 2, mal.syncedGeneration, "the same value does not reload again")

        assertEquals(TitleLanguage.ENGLISH, settings(store, mal = true).titleLanguage, "persists")
        assertEquals(
            TitleLanguage.ROMAJI,
            settings(store, mal = false).titleLanguage,
            "the AniList-synced value is untouched",
        )
    }

    @Test
    fun signingOutOfMalResetsTheLocalLanguages() {
        val store = FakeSettingsStore()
        val mal = settings(store, mal = true)
        mal.titleLanguage = TitleLanguage.NATIVE
        mal.staffNameLanguage = StaffNameLanguage.ROMAJI

        mal.clearSynced()

        val next = settings(store, mal = true)
        assertEquals(TitleLanguage.ROMAJI, next.titleLanguage)
        assertEquals(StaffNameLanguage.ROMAJI_WESTERN, next.staffNameLanguage)
    }

    @Test
    fun aniListStillUsesTheSyncedLanguages() {
        val store = FakeSettingsStore()
        val aniList = settings(store, mal = false)

        aniList.titleLanguage = TitleLanguage.NATIVE

        assertEquals(TitleLanguage.NATIVE, aniList.titleLanguage)
        assertEquals(TitleLanguage.ROMAJI, settings(store, mal = true).titleLanguage, "MAL's local value is separate")
    }
}
