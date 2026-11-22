package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.media.domain.ProviderId

/** A link attempt started from Settings; ten minutes is enough to finish OAuth. */
internal const val PendingLinkTtlSeconds = 600L

/**
 * The provider the user tapped "Link" for. Persisted because the process may
 * die while the browser is open; consumed on the callback (success, error or
 * cancellation) and never mixed with the session's own pending auth.
 */
internal data class PendingLink(
    val provider: ProviderId,
    val createdAtEpochSeconds: Long,
) {
    fun isValid(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds - createdAtEpochSeconds <= PendingLinkTtlSeconds
}

internal interface PendingLinkStore {
    fun load(): PendingLink?
    fun save(pending: PendingLink)
    fun clear()
}

/** The minimum identity the linked-account card shows. */
internal data class LinkedViewer(
    val id: Int,
    val name: String,
    val avatarUrl: String? = null,
    val scoreFormat: ScoreFormat,
)

/**
 * The one non-session account that only receives copies. Its token lives in a
 * separate storage namespace, never in the session's per-provider storage.
 */
internal data class LinkedAccount(
    val provider: ProviderId,
    val viewer: LinkedViewer,
)

/** Persists the linked viewer + the local mirror preferences. */
internal interface LinkedAccountStore {
    fun load(): LinkedAccount?
    fun save(account: LinkedAccount)
    fun clear()

    fun loadLostAccess(): ProviderId?
    fun saveLostAccess(provider: ProviderId)
    fun clearLostAccess()

    fun loadAutoCopy(): Boolean
    fun saveAutoCopy(enabled: Boolean)
}
