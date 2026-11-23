package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat

/** One signed-in account, as the account pages and the shell need it. */
internal data class ConnectedAccount(
    val provider: ProviderId,
    val viewerId: Int,
    val name: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val scoreFormat: ScoreFormat,
)

/**
 * The only token seam the mirror clients get. They are constructed bound to
 * one provider, so a writer can never ask for the other service's token.
 */
internal fun interface MirrorTokenSource {
    suspend fun mirrorToken(provider: ProviderId): AuthToken?
}
