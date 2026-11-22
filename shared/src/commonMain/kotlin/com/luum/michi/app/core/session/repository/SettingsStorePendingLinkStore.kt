package com.luum.michi.app.core.session.repository

import com.luum.michi.app.core.session.domain.PendingLink
import com.luum.michi.app.core.session.domain.PendingLinkStore
import com.luum.michi.app.core.storage.domain.SettingsStore
import com.luum.michi.app.core.media.domain.ProviderId

/**
 * [PendingLinkStore] over the key/value [SettingsStore]. Provider values and
 * epoch seconds contain no `|`, so malformed values are simply ignored.
 */
internal class SettingsStorePendingLinkStore(
    private val store: SettingsStore,
) : PendingLinkStore {

    override fun load(): PendingLink? {
        val raw = store.getString(Key)?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split('|')
        if (parts.size != 2 || parts[0].isBlank()) return null
        val createdAt = parts[1].toLongOrNull() ?: return null
        return PendingLink(provider = ProviderId(parts[0]), createdAtEpochSeconds = createdAt)
    }

    override fun save(pending: PendingLink) {
        store.putString(Key, "${pending.provider.value}|${pending.createdAtEpochSeconds}")
    }

    override fun clear() {
        store.putString(Key, "")
    }

    private companion object {
        const val Key = "pending_link"
    }
}
