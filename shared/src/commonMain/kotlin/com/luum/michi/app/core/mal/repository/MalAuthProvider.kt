package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.OAuthBrowser
import com.luum.michi.app.core.auth.domain.ProviderCapabilities
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.mal.domain.MalPendingAuthTtlSeconds
import com.luum.michi.app.core.mal.domain.PendingMalAuth
import com.luum.michi.app.core.mal.domain.PendingMalAuthStore
import com.luum.michi.app.core.mal.domain.generatePkce
import com.luum.michi.app.core.mal.domain.parseMalOAuthCallback
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.Viewer

/**
 * MyAnimeList implementation of [AuthProvider]: authorization-code grant with
 * PKCE (`plain`), refresh and viewer lookup. Registered whenever `malClientId`
 * is configured; the list reads/writes live in the MAL repositories.
 */
internal class MalAuthProvider(
    private val browser: OAuthBrowser,
    private val http: MalHttp,
    private val pendingStore: PendingMalAuthStore,
    private val now: () -> Long = { currentEpochSeconds() },
) : AuthProvider {

    override val id: ProviderId = ProviderId.Mal
    override val label: String = "MyAnimeList"
    override val isConfigured: Boolean get() = MalOAuthConfig.isConfigured
    override val capabilities: ProviderCapabilities = ProviderCapabilities.Mal

    override fun open() {
        val pkce = generatePkce()
        pendingStore.save(
            PendingMalAuth(
                verifier = pkce.verifier,
                state = pkce.state,
                createdAtEpochSeconds = now(),
            ),
        )
        browser.open(MalOAuthConfig.buildAuthorizeUrl(pkce))
    }

    override fun ownsCallback(uri: String): Boolean = parseMalOAuthCallback(uri) != null

    override fun discardPending() {
        pendingStore.clear()
    }

    override suspend fun handleCallback(uri: String): NetworkResult<AuthToken>? {
        val callback = parseMalOAuthCallback(uri) ?: return null
        val pending = pendingStore.load()

        val code = callback.code
        if (code == null) {
            // Cancelled/denied login: the session must not change, but drop the
            // verifier when this is the attempt we started.
            if (pending != null && callback.state == pending.state) {
                pendingStore.clear()
            }
            return null
        }

        // The code is single-use: never leave a verifier behind, even when the
        // state/expiry check rejects the callback.
        pendingStore.clear()
        val verified = pending?.takeIf {
            now() - it.createdAtEpochSeconds <= MalPendingAuthTtlSeconds &&
                callback.state == it.state
        } ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))

        return http.exchangeCode(code, verified.verifier)
    }

    override suspend fun refresh(token: AuthToken): NetworkResult<AuthToken>? =
        token.refreshToken?.let { http.refresh(it) }

    override suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer> =
        http.fetchViewer(token)
}
