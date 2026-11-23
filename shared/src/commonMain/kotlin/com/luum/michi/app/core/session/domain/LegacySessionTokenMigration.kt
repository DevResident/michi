package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId

/**
 * One-time move from the NSUserDefaults session namespaces to the platform's
 * secure storage. The existing secure token wins when both exist; the old keys
 * are cleared only after the target storage proves it kept the token.
 *
 * Idempotent: a second run finds every legacy storage empty.
 */
internal class LegacySessionTokenMigration(
    private val providers: Set<ProviderId>,
    private val sessionStorages: Map<ProviderId, TokenStorage>,
    private val legacySessionStorages: Map<ProviderId, TokenStorage>,
) {
    suspend fun migrate() {
        for (provider in providers) {
            val legacy = legacySessionStorages[provider] ?: continue
            val token = legacy.load() ?: continue
            val target = sessionStorages[provider] ?: continue
            val current = target.load()
            val migrated = if (current == null) {
                target.save(token)
                // save() is silent on failure, so the only proof is a read-back.
                target.load() == token
            } else {
                true
            }
            if (migrated) legacy.clear()
        }
    }
}
