package com.luum.michi.app.core.mal.domain

/** A pending PKCE attempt expires after ten minutes. */
internal const val MalPendingAuthTtlSeconds = 600L

/**
 * The verifier/state of a login that is currently in the browser. Persisted
 * because the process may die while the Custom Tab is open; cleared as soon as
 * the callback is used (success, error or cancellation).
 */
internal data class PendingMalAuth(
    val verifier: String,
    val state: String,
    val createdAtEpochSeconds: Long,
)

/** Persists one pending MAL login. Implemented over `SettingsStore`. */
internal interface PendingMalAuthStore {
    fun load(): PendingMalAuth?
    fun save(pending: PendingMalAuth)
    fun clear()
}
