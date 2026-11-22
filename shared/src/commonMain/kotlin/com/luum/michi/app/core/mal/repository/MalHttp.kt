package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.MalEntryStatus
import com.luum.michi.app.core.mal.domain.MalEntryUpdate
import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.domain.MalNodeInfo
import com.luum.michi.app.core.mal.domain.MalOAuthConfig
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.AniListNetworkPolicy
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.core.network.repository.AniListRateLimiter
import com.luum.michi.app.core.network.repository.throwableToNetworkError
import com.luum.michi.app.core.session.domain.Viewer
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
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

/*
 * MAL only returns some list-status subfields when they are named explicitly
 * (the API says "by default, the API doesn't return all fields"). Without
 * priority, comments and the rewatch count, the editor would open with empty
 * values. Always name every subfield Michi reads.
 */
private const val MalAnimeStatusFields =
    "status,score,num_episodes_watched,is_rewatching,updated_at,start_date,finish_date," +
        "priority,num_times_rewatched,comments"
private const val MalMangaStatusFields =
    "status,score,num_chapters_read,num_volumes_read,is_rereading,updated_at,start_date,finish_date," +
        "priority,num_times_reread,comments"

/*
 * The work's own fields, next to list_status: they only matter for works
 * AniList does not have (MAL-only entries), which the list then builds from
 * MAL's data. `id` is always returned.
 */
private const val MalAnimeNodeFields =
    "title,alternative_titles,main_picture,media_type,status,num_episodes,start_season,nsfw"
private const val MalMangaNodeFields =
    "title,alternative_titles,main_picture,media_type,status,num_chapters,num_volumes,start_date,nsfw"

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

    /** The viewer's entry, or null when the media exists but is not in the list. */
    suspend fun fetchEntry(token: AuthToken, ref: MalRef): NetworkResult<MalEntryStatus?> =
        fetchWorkEntry(token, ref).map { it.status }

    /** The work's title and totals with the viewer's entry: what a MAL-only editor needs. */
    suspend fun fetchWorkEntry(token: AuthToken, ref: MalRef): NetworkResult<MalWorkEntry> = withRetry(
        request = {
            httpClient.get(entryUrl(ref, includeStatus = true)) {
                header("Authorization", token.authorizationHeaderValue)
            }
        },
        parse = { response, body ->
            when {
                response.status == HttpStatusCode.NotFound ->
                    NetworkResult.Failure(NetworkError.NotFound("MAL media", ref.malId))
                !response.status.isSuccess() -> NetworkResult.Failure(httpError(response, body))
                else -> {
                    val dto = MalJson.decodeFromString<MalMediaEntryResponseDto>(body)
                    NetworkResult.Success(
                        MalWorkEntry(
                            title = dto.title,
                            episodes = dto.numEpisodes?.takeIf { it > 0 },
                            chapters = dto.numChapters?.takeIf { it > 0 },
                            volumes = dto.numVolumes?.takeIf { it > 0 },
                            status = dto.myListStatus?.toEntryStatus(ref.type),
                        ),
                    )
                }
            }
        },
    )

    suspend fun patchEntry(
        token: AuthToken,
        ref: MalRef,
        update: MalEntryUpdate,
    ): NetworkResult<MalEntryStatus> = withRetry(
        request = {
            httpClient.patch(entryUrl(ref, includeStatus = false)) {
                header("Authorization", token.authorizationHeaderValue)
                setBody(FormDataContent(update.toForm(ref.type)))
            }
        },
        parse = { response, body ->
            when {
                response.status == HttpStatusCode.NotFound ->
                    NetworkResult.Failure(NetworkError.NotFound("MAL media", ref.malId))
                !response.status.isSuccess() -> NetworkResult.Failure(httpError(response, body))
                else -> {
                    val dto = MalJson.decodeFromString<MalListStatusDto>(body)
                    NetworkResult.Success(dto.toEntryStatus(ref.type))
                }
            }
        },
    )

    suspend fun deleteEntry(token: AuthToken, ref: MalRef): NetworkResult<Unit> = withRetry(
        request = {
            httpClient.delete(entryUrl(ref, includeStatus = false)) {
                header("Authorization", token.authorizationHeaderValue)
            }
        },
        parse = { response, body ->
            when {
                // MAL's docs say 404 when the entry is absent; live API also
                // returns 200. Both are a successful, idempotent deletion.
                response.status.isSuccess() ||
                    response.status == HttpStatusCode.NotFound -> NetworkResult.Success(Unit)
                else -> NetworkResult.Failure(httpError(response, body))
            }
        },
    )

    private fun entryUrl(ref: MalRef, includeStatus: Boolean): String {
        val path = if (ref.type == MediaType.MANGA) "manga" else "anime"
        if (!includeStatus) return "${MalOAuthConfig.ApiBaseUrl}/$path/${ref.malId}/my_list_status"
        val fields = if (ref.type == MediaType.MANGA) {
            "my_list_status{$MalMangaStatusFields},num_chapters,num_volumes,title"
        } else {
            "my_list_status{$MalAnimeStatusFields},num_episodes,title"
        }
        return URLBuilder("${MalOAuthConfig.ApiBaseUrl}/$path/${ref.malId}").apply {
            parameters.append("fields", fields)
        }.buildString()
    }

    private fun MalEntryUpdate.toForm(type: MediaType): Parameters = Parameters.build {
        status?.let { append("status", it) }
        score?.let { append("score", it.toString()) }
        priority?.let { append("priority", it.toString()) }
        comments?.let { append("comments", it) }
        startDate?.let { append("start_date", it) }
        finishDate?.let { append("finish_date", it) }
        if (type == MediaType.MANGA) {
            progress?.let { append("num_chapters_read", it.toString()) }
            progressVolumes?.let { append("num_volumes_read", it.toString()) }
            repeat?.let { append("num_times_reread", it.toString()) }
            isRewatching?.let { append("is_rereading", it.toString()) }
        } else {
            progress?.let { append("num_watched_episodes", it.toString()) }
            repeat?.let { append("num_times_rewatched", it.toString()) }
            isRewatching?.let { append("is_rewatching", it.toString()) }
        }
    }

    private fun httpError(response: HttpResponse, body: String?): NetworkError =
        if (response.status == HttpStatusCode.Unauthorized) {
            NetworkError.Unauthorized(body)
        } else {
            NetworkError.Http(response.status.value, body)
        }

    private fun MalListStatusDto.toEntryStatus(type: MediaType): MalEntryStatus = MalEntryStatus(
        status = status,
        score = score,
        progress = if (type == MediaType.MANGA) numChaptersRead else numEpisodesWatched,
        progressVolumes = if (type == MediaType.MANGA) numVolumesRead else null,
        isRewatching = isRewatching,
        isRereading = isRereading,
        updatedAt = updatedAt,
        startDate = startDate,
        finishDate = finishDate,
        priority = priority,
        numTimesRewatched = numTimesRewatched,
        numTimesReread = numTimesReread,
        comments = comments,
    )

    private suspend fun loadList(
        token: AuthToken,
        path: String,
        isManga: Boolean,
    ): NetworkResult<List<MalListEntry>> {
        val entries = mutableListOf<MalListEntry>()
        val visited = mutableSetOf<String>()
        val listFields = listFields(isManga)
        var url = buildListUrl(path, listFields)
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
                    url = ensureListQuery(next, listFields)
                }
            }
        }
        return NetworkResult.Success(entries)
    }

    private fun listFields(isManga: Boolean): String =
        "list_status{${if (isManga) MalMangaStatusFields else MalAnimeStatusFields}}," +
            if (isManga) MalMangaNodeFields else MalAnimeNodeFields

    private fun buildListUrl(path: String, fields: String): String =
        URLBuilder("${MalOAuthConfig.ApiBaseUrl}/users/@me/$path").apply {
            parameters.append("fields", fields)
            parameters.append("limit", MalListPageLimit.toString())
            parameters.append("nsfw", "true")
        }.buildString()

    /** The next page must stay on MAL's API over HTTPS: the Bearer never follows a foreign URL. */
    private fun isAllowedPagingUrl(url: String): Boolean = try {
        val parsed = URLBuilder(url).build()
        parsed.protocol.name.equals("https", ignoreCase = true) &&
            parsed.host.equals("api.myanimelist.net", ignoreCase = true) &&
            parsed.port == 443
    } catch (_: Throwable) {
        false
    }

    private fun ensureListQuery(url: String, fields: String): String = URLBuilder(url).apply {
        if (parameters["fields"] == null) parameters.append("fields", fields)
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
                node = item.node.toInfo(),
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

/** One MAL work as the entry GET returns it; counts MAL sends as 0 are null. */
internal data class MalWorkEntry(
    val title: String?,
    val episodes: Int?,
    val chapters: Int?,
    val volumes: Int?,
    val status: MalEntryStatus?,
)

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
    val title: String? = null,
    @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitlesDto? = null,
    @SerialName("main_picture") val mainPicture: MalPictureDto? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    @SerialName("num_volumes") val numVolumes: Int? = null,
    @SerialName("start_season") val startSeason: MalStartSeasonDto? = null,
    @SerialName("start_date") val startDate: String? = null,
    val nsfw: String? = null,
)

@Serializable
private data class MalAlternativeTitlesDto(
    val en: String? = null,
    val ja: String? = null,
)

@Serializable
private data class MalPictureDto(
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
private data class MalStartSeasonDto(
    val year: Int? = null,
    val season: String? = null,
)

/** MAL sends 0 for an unknown episode/chapter/volume count. */
private fun Int?.positiveOrNull(): Int? = this?.takeIf { it > 0 }

private fun MalListNodeDto.toInfo(): MalNodeInfo = MalNodeInfo(
    title = title,
    englishTitle = alternativeTitles?.en?.takeIf { it.isNotBlank() },
    japaneseTitle = alternativeTitles?.ja?.takeIf { it.isNotBlank() },
    pictureMedium = mainPicture?.medium,
    pictureLarge = mainPicture?.large,
    mediaType = mediaType,
    status = status,
    episodes = numEpisodes.positiveOrNull(),
    chapters = numChapters.positiveOrNull(),
    volumes = numVolumes.positiveOrNull(),
    startYear = startSeason?.year ?: startDate?.take(4)?.toIntOrNull(),
    startSeason = startSeason?.season,
    nsfw = nsfw,
)

@Serializable
private data class MalMediaEntryResponseDto(
    val title: String? = null,
    @SerialName("my_list_status") val myListStatus: MalListStatusDto? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    @SerialName("num_volumes") val numVolumes: Int? = null,
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
    val comments: String? = null,
)
