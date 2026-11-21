package com.luum.michi.app.core.auth.domain

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.Viewer

/**
 * One login service. The app only keeps one active provider at a time; the
 * provider knows its own OAuth flow, token refresh and viewer lookup.
 */
internal interface AuthProvider {
    val id: ProviderId

    /** Brand name, never translated ("AniList"). */
    val label: String

    val isConfigured: Boolean

    /** What this provider's account supports; the UI hides the rest. */
    val capabilities: ProviderCapabilities

    /** Opens the provider's authorization page. */
    fun open()

    /** null when [uri] does not belong to this provider; may do network (code exchange). */
    suspend fun handleCallback(uri: String): NetworkResult<AuthToken>?

    /** null when the provider does not refresh (AniList). */
    suspend fun refresh(token: AuthToken): NetworkResult<AuthToken>?

    suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer>
}
