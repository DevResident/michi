package com.luum.michi.app.core.mal.domain

import io.ktor.http.URLBuilder

/**
 * OAuth client configuration for MyAnimeList. The `ClientId` is generated from
 * `local.properties` at build time (see `MalBuildConfig`), so it is not
 * committed to source control.
 *
 * MAL only supports PKCE with the `plain` method: the challenge is the verifier
 * itself. The redirect URI is the same custom scheme AniList uses.
 */
internal object MalOAuthConfig {
    val ClientId: String = MalBuildConfig.ClientId
    const val RedirectUri = "michi://oauth/callback"

    const val AuthorizeEndpoint = "https://myanimelist.net/v1/oauth2/authorize"
    const val TokenEndpoint = "https://myanimelist.net/v1/oauth2/token"
    const val ApiBaseUrl = "https://api.myanimelist.net/v2"

    /** MAL's authorize URL for one PKCE attempt. */
    fun buildAuthorizeUrl(pkce: Pkce, clientId: String = ClientId): String =
        URLBuilder(AuthorizeEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("code_challenge", pkce.verifier)
            parameters.append("code_challenge_method", "plain")
            parameters.append("state", pkce.state)
            parameters.append("redirect_uri", RedirectUri)
        }.buildString()

    internal val isConfigured: Boolean
        get() = ClientId.isNotBlank()
}
