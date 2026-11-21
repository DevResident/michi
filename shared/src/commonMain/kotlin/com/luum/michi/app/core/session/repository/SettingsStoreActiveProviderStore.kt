package com.luum.michi.app.core.session.repository

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.session.domain.ActiveProviderStore
import com.luum.michi.app.core.storage.domain.SettingsStore
import com.luum.michi.app.core.storage.domain.SettingsStoreKeys

/** [ActiveProviderStore] over the key/value [SettingsStore]. */
internal class SettingsStoreActiveProviderStore(
    private val store: SettingsStore,
) : ActiveProviderStore {

    override fun load(): ProviderId =
        store.getString(SettingsStoreKeys.ActiveProvider)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderId)
            ?: ProviderId.AniList

    override fun save(provider: ProviderId) {
        store.putString(SettingsStoreKeys.ActiveProvider, provider.value)
    }

    override fun clear() {
        store.putString(SettingsStoreKeys.ActiveProvider, "")
    }
}
