package com.luum.michi.app.core

import com.luum.michi.app.core.storage.domain.SettingsStoreKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageKeysTest {

    @Test
    fun storeKeysAreUnique() {
        val keys = setOf(
            SettingsStoreKeys.ThemeMode,
            SettingsStoreKeys.ThemePalette,
            SettingsStoreKeys.Font,
            SettingsStoreKeys.Language,
            SettingsStoreKeys.StartTab,
            SettingsStoreKeys.StartAnimeSection,
            SettingsStoreKeys.StartMangaSection,
            SettingsStoreKeys.StartCatalogType,
            SettingsStoreKeys.ActiveProvider,
            SettingsStoreKeys.LinkedAccount,
            SettingsStoreKeys.LinkedLostAccess,
            SettingsStoreKeys.LinkedAutoCopy,
        )
        assertEquals(12, keys.size)
    }
}
