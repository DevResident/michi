package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId

/**
 * One-time move from the 11.6 linked-token namespaces to each provider's
 * session storage. The session token always wins when both exist, and the
 * legacy namespace is cleared even then (it may still be a live token).
 *
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
            val session = sessionStorages[provider]
            if (session != null && session.load() == null) {
                session.save(token)
            }
            legacy.clear()
        }
    }
}
