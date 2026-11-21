package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.session.repository.SettingsStoreActiveProviderStore
import com.luum.michi.app.settings.FakeSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveProviderStoreTest {

    @Test
    fun defaultsToAniList() {
        val store = SettingsStoreActiveProviderStore(FakeSettingsStore())
        assertEquals(ProviderId.AniList, store.load())
    }

    @Test
    fun roundTripsAndClearsToTheDefault() {
        val store = SettingsStoreActiveProviderStore(FakeSettingsStore())
        val other = ProviderId("other")

        store.save(other)
        assertEquals(other, store.load())

        store.clear()
        assertEquals(ProviderId.AniList, store.load())
    }
}
