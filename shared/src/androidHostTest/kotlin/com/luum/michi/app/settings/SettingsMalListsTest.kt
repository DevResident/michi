package com.luum.michi.app.settings

import com.luum.michi.app.settings.domain.model.ListSort
import com.luum.michi.app.settings.ui.state.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mis listas with MAL: order/sections have no AniList endpoint, so they are
 * normal local preferences. They must hydrate from the store, persist on edit
 * and survive a new holder without scheduling a save.
 */
class SettingsMalListsTest {

    @Test
    fun malListsReadWriteAndReloadFromTheLocalStore() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val store = FakeSettingsStore().apply {
                preload("list_sort", "TITLE")
                preload("split_completed_anime", false)
                preload("split_completed_manga", true)
                preload("persist_list_sort", false)
            }
            val repository = FakeSettingsRepository()
            val state = SettingsState(
                repository = repository,
                store = store,
                scope = scope,
                canSync = false,
                useLocalAdultContent = true,
            )

            assertEquals(ListSort.TITLE, state.listSort)
            assertEquals(false, state.splitCompletedAnime)
            assertEquals(true, state.splitCompletedManga)
            assertEquals(false, state.persistListSort)

            state.listSort = ListSort.SCORE
            state.splitCompletedAnime = true
            state.splitCompletedManga = false
            state.persistListSort = true

            assertEquals("SCORE", store.getString("list_sort"))
            assertEquals(true, store.getBoolean("split_completed_anime", false))
            assertEquals(false, store.getBoolean("split_completed_manga", true))
            assertEquals(true, store.getBoolean("persist_list_sort", false))

            val reloaded = SettingsState(
                repository = repository,
                store = store,
                scope = scope,
                canSync = false,
                useLocalAdultContent = true,
            )
            assertEquals(ListSort.SCORE, reloaded.listSort)
            assertEquals(true, reloaded.splitCompletedAnime)
            assertEquals(false, reloaded.splitCompletedManga)
            assertEquals(true, reloaded.persistListSort)
            assertEquals(0, repository.saveCallCount, "MAL has no settings endpoint to save to")
        } finally {
            scope.cancel()
        }
    }
}
