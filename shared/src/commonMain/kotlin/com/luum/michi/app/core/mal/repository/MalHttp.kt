package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.AniListNetworkPolicy
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.repository.AniListRateLimiter
import com.luum.michi.app.core.network.repository.throwableToNetworkError
import com.luum.michi.app.core.session.domain.Viewer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
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

private const val MalListPageLimit = 1000
private const val MalMaxListPages = 100

/**
 * MyAnimeList's REST calls: code exchange, refresh, viewer and user lists.
 * Uses the shared Ktor client and its own conservative 60/min limiter (MAL does
 * not document a limit); that limiter is deliberately not exposed as the app's
 * NetworkThrottle, which stays AniList's. [clientId]/[redirectUri] are
 * injectable so tests do not depend on the generated build config.
 */
internal class MalHttp(
    private val httpClient: HttpClient,
    private val clientId: String = MalOAuthConfig.ClientId,
    private val redirectUri: String = MalOAuthConfig.RedirectUri,
    private val rateLimiter: AniListRateLimiter = AniListRateLimiter(windowLimit = 60),
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

    suspend fun fetchViewer(token: AuthToken): NetworkResult<Viewer> = withRetry(
        request = {
            httpClient.get("${MalOAuthConfig.ApiBaseUrl}/users/@me") {
                header("Authorization", token.authorizationHeaderValue)
                parameter("fields", "id,name,picture")
            }
        },
        parse = { response, body ->
            if (!response.status.isSuccess()) {
                NetworkResult.Failure(
                    if (response.status == HttpStatusCode.Unauthorized) {
                        NetworkError.Unauthorized(body)
                    } else {
                        NetworkError.Http(response.status.value, body)
                    },
                )
            } else {
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
        },
    )

    suspend fun loadAnimeList(token: AuthToken): NetworkResult<List<MalListEntry>> =
        loadList(token, path = "animelist", isManga = false)

    suspend fun loadMangaList(token: AuthToken): NetworkResult<List<MalListEntry>> =
        loadList(token, path = "mangalist", isManga = true)

    private suspend fun loadList(
        token: AuthToken,
        path: String,
        isManga: Boolean,
    ): NetworkResult<List<MalListEntry>> {
        val entries = mutableListOf<MalListEntry>()
        val visited = mutableSetOf<String>()
        var url = buildListUrl(path)
        var pages = 0

        while (true) {
            if (!visited.add(url) || pages >= MalMaxListPages) {
                return NetworkResult.Failure(
                    NetworkError.Unknown(IllegalStateException("MAL list pagination loop")),
                )
            }
            pages++

            val page = withRetry(
                request = {
                    httpClient.get(url) {
                        header("Authorization", token.authorizationHeaderValue)
                    }
                },
                parse = { response, body -> parseListPage(response, body, isManga) },
            )
            when (page) {
                is NetworkResult.Failure -> return page
                is NetworkResult.Success -> {
                    entries += page.value.entries
                    val next = page.value.next ?: break
                    if (!isAllowedPagingUrl(next)) {
                        return NetworkResult.Failure(
                            NetworkError.Http(
                                400,
                                "MAL paging.next points to an unexpected host",
                            ),
                        )
                    }
                    url = ensureListQuery(next)
                }
            }
        }
        return NetworkResult.Success(entries)
    }

    private fun buildListUrl(path: String): String =
        URLBuilder("${MalOAuthConfig.ApiBaseUrl}/users/@me/$path").apply {
            parameters.append("fields", "list_status")
            parameters.append("limit", MalListPageLimit.toString())
            parameters.append("nsfw", "true")
        }.buildString()

    /** The next page must stay on MAL's API over HTTPS: the Bearer never follows a foreign URL. */
    private fun isAllowedPagingUrl(url: String): Boolean = try {
        val parsed = URLBuilder(url).build()
        parsed.protocol.name.equals("https", ignoreCase = true) &&
            parsed.host.equals("api.myanimelist.net", ignoreCase = true)
    } catch (_: Throwable) {
        false
    }

    private fun ensureListQuery(url: String): String = URLBuilder(url).apply {
        if (parameters["fields"] == null) parameters.append("fields", "list_status")
        if (parameters["nsfw"] == null) parameters.append("nsfw", "true")
    }.buildString()

    private fun parseListPage(
        response: HttpResponse,
        body: String,
        isManga: Boolean,
    ): NetworkResult<MalListPage> {
        if (!response.status.isSuccess()) {
            return NetworkResult.Failure(
                if (response.status == HttpStatusCode.Unauthorized) {
                    NetworkError.Unauthorized(body)
                } else {
                    NetworkError.Http(response.status.value, body)
                },
            )
        }

        val dto = MalJson.decodeFromString<MalListResponseDto>(body)
        val entries = dto.data.mapNotNull { item ->
            val status = item.listStatus ?: return@mapNotNull null
            MalListEntry(
                malId = item.node.id,
                status = status.status,
                score = status.score,
                progress = if (isManga) status.numChaptersRead else status.numEpisodesWatched,
                progressVolumes = if (isManga) status.numVolumesRead else null,
                updatedAt = status.updatedAt,
                startDate = status.startDate,
                finishDate = status.finishDate,
                priority = status.priority,
            )
        }
        return NetworkResult.Success(MalListPage(entries, dto.paging?.next))
    }

    private suspend fun postToken(
        parameters: Parameters,
        badRequestIsUnauthorized: Boolean,
    ): NetworkResult<AuthToken> = withRetry(
        request = {
            httpClient.post(MalOAuthConfig.TokenEndpoint) {
                setBody(FormDataContent(parameters))
            }
        },
        parse = { response, body ->
            if (!response.status.isSuccess()) {
                NetworkResult.Failure(
                    when {
                        response.status == HttpStatusCode.Unauthorized ->
                            NetworkError.Unauthorized(body)
                        response.status == HttpStatusCode.BadRequest && badRequestIsUnauthorized ->
                            NetworkError.Unauthorized(body)
                        else -> NetworkError.Http(response.status.value, body)
                    },
                )
            } else {
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
        },
    )

    private suspend fun <T> withRetry(
        request: suspend () -> HttpResponse,
        retriesLeft: Int = AniListNetworkPolicy.MAX_RATE_LIMIT_RETRIES,
        parse: suspend (HttpResponse, String) -> NetworkResult<T>,
    ): NetworkResult<T> = guarded {
        val response = rateLimiter.withPermit { request() }
        val body = response.bodyAsText()
        if (response.status == HttpStatusCode.TooManyRequests) {
            val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                ?: AniListNetworkPolicy.DEFAULT_RETRY_AFTER_SECONDS
            rateLimiter.onRateLimited(retryAfter)
            if (retriesLeft > 0) {
                return@guarded withRetry(request, retriesLeft - 1, parse)
            }
            return@guarded NetworkResult.Failure(
                NetworkError.RateLimited(retryAfter.toInt()),
            )
        }
        parse(response, body)
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

private data class MalListPage(
    val entries: List<MalListEntry>,
    val next: String?,
)

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

@Serializable
private data class MalListResponseDto(
    val data: List<MalListEntryItemDto> = emptyList(),
    val paging: MalPagingDto? = null,
)

@Serializable
private data class MalPagingDto(
    val next: String? = null,
)

@Serializable
private data class MalListEntryItemDto(
    val node: MalListNodeDto,
    @SerialName("list_status") val listStatus: MalListStatusDto? = null,
)

@Serializable
private data class MalListNodeDto(
    val id: Int,
)

@Serializable
private data class MalListStatusDto(
    val status: String? = null,
    val score: Double = 0.0,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int = 0,
    @SerialName("num_chapters_read") val numChaptersRead: Int = 0,
    @SerialName("num_volumes_read") val numVolumesRead: Int = 0,
    @SerialName("is_rewatching") val isRewatching: Boolean = false,
    @SerialName("is_rereading") val isRereading: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("finish_date") val finishDate: String? = null,
    val priority: Int = 0,
    @SerialName("num_times_rewatched") val numTimesRewatched: Int = 0,
    @SerialName("num_times_reread") val numTimesReread: Int = 0,
)
