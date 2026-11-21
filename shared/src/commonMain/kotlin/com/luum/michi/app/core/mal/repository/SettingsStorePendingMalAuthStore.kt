package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.domain.PendingMalAuthStore
import com.luum.michi.app.core.storage.domain.SettingsStore

/**
 * [PendingMalAuthStore] over the key/value [SettingsStore]. The verifier and
 * state are base64url, so `|` is a safe separator; malformed values are ignored.
 */
internal class SettingsStorePendingMalAuthStore(
    private val store: SettingsStore,
) : PendingMalAuthStore {

    override fun load(): PendingMalAuth? {
        val raw = store.getString(Key)?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        if (parts[0].isBlank() || parts[1].isBlank()) return null
        val createdAt = parts[2].toLongOrNull() ?: return null
        return PendingMalAuth(
            verifier = parts[0],
            state = parts[1],
            createdAtEpochSeconds = createdAt,
        )
    }

    override fun save(pending: PendingMalAuth) {
        store.putString(
            Key,
            "${pending.verifier}|${pending.state}|${pending.createdAtEpochSeconds}",
        )
    }

    override fun clear() {
        store.putString(Key, "")
    }

    private companion object {
        const val Key = "mal_pending_auth"
    }
}
