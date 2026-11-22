package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.medialist.domain.toApiValue
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.MediaListDiskCache
import com.luum.michi.app.mediaList.repository.MediaListEntryDto

/**
 * Shared body of the two MAL list repositories: fetch all MAL pages, hydrate
 * the ids through AniList, compose the same DTO the AniList path caches, and
 * persist it under [ProviderId.Mal]. A hydration failure fails the whole load
 * (no partial list); the previous cache stays untouched.
 */
internal class MalListLoader(
    private val http: MalHttp,
    private val tokenProvider: suspend () -> AuthToken?,
    private val bridge: MediaIdBridge,
    private val cache: MediaListDiskCache? = null,
    private val onUnauthorized: suspend (AuthToken) -> Unit = {},
    private val onUnmatched: (MediaType, Int) -> Unit = { _, _ -> },
) {

    suspend fun load(type: MediaType, userId: Int): NetworkResult<List<MediaListEntryDto>> {
        val token = tokenProvider()
            ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))

        val entries = when (val result = fetch(type, token)) {
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                return result
            }
            is NetworkResult.Success -> result.value
        }

        if (entries.isEmpty()) {
            cache?.write(userId, type, emptyList(), ProviderId.Mal)
            return NetworkResult.Success(emptyList())
        }

        val media = when (val hydrated = bridge.hydrateByMalIds(entries.map { it.malId }, type)) {
            is NetworkResult.Failure -> return hydrated
            is NetworkResult.Success -> hydrated.value
        }

        val matched = entries.mapNotNull { entry ->
            media[entry.malId]?.let { entry.toDto(it) }
        }
        val unmatched = entries.size - matched.size
        if (unmatched > 0) onUnmatched(type, unmatched)

        cache?.write(userId, type, matched, ProviderId.Mal)
        return NetworkResult.Success(matched)
    }

    suspend fun cached(type: MediaType, userId: Int): List<MediaListEntryDto>? =
        cache?.read(userId, type, ProviderId.Mal)

    private suspend fun fetch(
        type: MediaType,
        token: AuthToken,
    ): NetworkResult<List<MalListEntry>> = when (type) {
        MediaType.ANIME -> http.loadAnimeList(token)
        MediaType.MANGA -> http.loadMangaList(token)
    }
}

private fun MalListEntry.toDto(media: MediaListMediaDto): MediaListEntryDto = MediaListEntryDto(
    id = media.id,
    status = malListStatusToMediaListStatus(status).toApiValue(),
    score = score,
    progress = progress,
    progressVolumes = progressVolumes,
    updatedAt = parseMalUpdatedAt(updatedAt),
    startedAt = parseMalFuzzyDate(startDate),
    completedAt = parseMalFuzzyDate(finishDate),
    priority = priority,
    media = media,
)
