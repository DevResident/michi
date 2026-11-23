package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId

/**
 * One-time move from the 11.6 linked-token namespaces to each provider's
 * session storage. The session token always wins when both exist; otherwise the
 * legacy keys are cleared only after the target storage proves it kept the
 * token (save() is silent on failure, so the migration reads it back first).
 * Idempotent: a second run finds every legacy storage empty.
 */
internal class LegacyTokenMigration(
    private val providers: Set<ProviderId>,
    private val sessionStorages: Map<ProviderId, TokenStorage>,
    private val legacyStorages: Map<ProviderId, TokenStorage>,
) {
    suspend fun migrate() {
        for (provider in providers) {
            val legacy = legacyStorages[provider] ?: continue
            val token = legacy.load() ?: continue
            val session = sessionStorages[provider] ?: continue
            val current = session.load()
            val migrated = if (current == null) {
                session.save(token)
                // save() is silent on failure, so the only proof is a read-back.
                session.load() == token
            } else {
                true
            }
            if (migrated) legacy.clear()
        }
    }
}
