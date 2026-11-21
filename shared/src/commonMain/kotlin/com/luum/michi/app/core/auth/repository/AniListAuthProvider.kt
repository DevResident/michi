package com.luum.michi.app.core.auth.repository

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.AniListOAuthConfig
import com.luum.michi.app.core.auth.domain.OAuthBrowser
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.parseAniListOAuthCallback
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.core.session.domain.AniListViewerRepository
import com.luum.michi.app.core.session.domain.Viewer

/**
 * AniList implementation of [AuthProvider]. Wraps the existing implicit-grant
 * parser, browser and viewer query; AniList does not refresh tokens.
 */
internal class AniListAuthProvider(
    private val browser: OAuthBrowser,
    private val viewerRepository: AniListViewerRepository,
) : AuthProvider {

    override val id: ProviderId = ProviderId.AniList
    override val label: String = "AniList"
    override val isConfigured: Boolean get() = AniListOAuthConfig.isConfigured
    override val capabilities: ProviderCapabilities = ProviderCapabilities.AniList

    override fun open() = browser.open(AniListOAuthConfig.buildAuthorizeUrl())

    override suspend fun handleCallback(uri: String): NetworkResult<AuthToken>? =
        parseAniListOAuthCallback(uri)?.let { NetworkResult.Success(it) }

    override suspend fun refresh(token: AuthToken): NetworkResult<AuthToken>? = null

    /**
     * The shared GraphQL client already carries the active token through
     * `SessionManager.validToken(ProviderId.AniList)`; [token] keeps the contract
     * provider-neutral.
     */
    override suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer> =
        viewerRepository.fetchViewer().map { it.copy(provider = id) }
}
