package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.throwableToNetworkError
import com.luum.michi.app.core.session.domain.Viewer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val MalJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * MyAnimeList's REST calls needed for login: code exchange, refresh and viewer.
 * Uses the shared Ktor client (no GraphQL, no rate limiter until the lists land
 * in 11.4b). [clientId]/[redirectUri] are injectable so tests do not depend on
 * the generated build config.
 */
internal class MalHttp(
    private val httpClient: HttpClient,
    private val clientId: String = MalOAuthConfig.ClientId,
    private val redirectUri: String = MalOAuthConfig.RedirectUri,
) {

    suspend fun exchangeCode(code: String, codeVerifier: String): NetworkResult<AuthToken> =
        postToken(
            parameters = Parameters.build {
                append("client_id", clientId)
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
            },
            badRequestIsUnauthorized = false,
        )

    suspend fun refresh(refreshToken: String): NetworkResult<AuthToken> =
        postToken(
            parameters = Parameters.build {
                append("client_id", clientId)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
            badRequestIsUnauthorized = true,
        )

    suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer> = guarded {
        val response = httpClient.get("${MalOAuthConfig.ApiBaseUrl}/users/@me") {
            header("Authorization", token.authorizationHeaderValue)
            parameter("fields", "id,name,picture")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return@guarded NetworkResult.Failure(
                if (response.status == HttpStatusCode.Unauthorized) {
                    NetworkError.Unauthorized(body)
                } else {
                    NetworkError.Http(response.status.value, body)
                },
            )
        }

        val dto = MalJson.decodeFromString<MalUserDto>(body)
        NetworkResult.Success(
            Viewer(
                id = dto.id,
                name = dto.name,
                avatarUrl = dto.picture,
                provider = ProviderId.Mal,
            ),
        )
    }

    private suspend fun postToken(
        parameters: Parameters,
        badRequestIsUnauthorized: Boolean,
    ): NetworkResult<AuthToken> = guarded {
        val response = httpClient.post(MalOAuthConfig.TokenEndpoint) {
            setBody(FormDataContent(parameters))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return@guarded NetworkResult.Failure(
                when {
                    response.status == HttpStatusCode.Unauthorized ->
                        NetworkError.Unauthorized(body)
                    response.status == HttpStatusCode.BadRequest && badRequestIsUnauthorized ->
                        NetworkError.Unauthorized(body)
                    else -> NetworkError.Http(response.status.value, body)
                },
            )
        }

        val dto = MalJson.decodeFromString<MalTokenDto>(body)
        NetworkResult.Success(
            AuthToken(
                accessToken = dto.accessToken,
                tokenType = dto.tokenType ?: "Bearer",
                expiresInSeconds = dto.expiresIn,
                savedAtEpochSeconds = currentEpochSeconds(),
                refreshToken = dto.refreshToken,
            ),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> NetworkResult<T>): NetworkResult<T> =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            NetworkResult.Failure(throwableToNetworkError(throwable))
        }
}

@Serializable
private data class MalTokenDto(
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class MalUserDto(
    val id: Int,
    val name: String,
    val picture: String? = null,
)
