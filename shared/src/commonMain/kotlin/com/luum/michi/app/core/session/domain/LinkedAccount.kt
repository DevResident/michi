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

/** The minimum identity an account row and a followed-account switch need. */
internal data class LinkedViewer(
    val id: Int,
    val name: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val scoreFormat: ScoreFormat,
)

/**
 * The connected account that is not followed. Its token lives in the same
 * per-provider session storage as every other connected account.
 */
internal data class LinkedAccount(
    val provider: ProviderId,
    val viewer: LinkedViewer,
) {
    fun toViewer(): Viewer = Viewer(
        provider = provider,
        id = viewer.id,
        name = viewer.name,
        avatarUrl = viewer.avatarUrl,
        bannerUrl = viewer.bannerUrl,
        scoreFormat = viewer.scoreFormat.name,
    )
}

internal fun Viewer.toLinkedViewer(): LinkedViewer = LinkedViewer(
    id = id,
    name = name,
    avatarUrl = avatarUrl,
    bannerUrl = bannerUrl,
    scoreFormat = com.luum.michi.app.core.medialist.domain.parseScoreFormat(scoreFormat),
)

/** Persists the other connected viewer + the local mirror preferences. */
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
