package com.luum.michi.app.mediaList.repository.mal

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.mal.repository.parseMalFuzzyDate
import com.luum.michi.app.core.mal.repository.parseMalUpdatedAt
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.dto.MediaListMediaDto
import com.luum.michi.app.core.medialist.domain.toApiValue
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.MediaListDiskCache
import com.luum.michi.app.mediaList.repository.MediaListEntryDto

private const val FullHydrationTtlSeconds = 24L * 60L * 60L

/**
 * Shared body of the two MAL list repositories: fetch all MAL pages, hydrate
 * the ids through AniList, compose the same DTO the AniList path caches, and
 * persist it under [ProviderId.Mal]. A hydration failure fails the whole load
 * (no partial list); the previous cache stays untouched.
 *
 * Media hydration is incremental: a normal reload reuses the cached AniList
 * media for known malIds and only asks AniList for new ones. A full hydration
 * happens at most once every 24 h per type, or when [forceHydration] (the
 * user's pull-to-refresh) asks for it. This keeps a +1 from re-fetching every
 * media in the list.
 */
internal class MalListLoader(
    private val http: MalHttp,
    private val tokenProvider: suspend () -> AuthToken?,
    private val bridge: MediaIdBridge,
    private val cache: MediaListDiskCache? = null,
    private val hydrationStore: MalHydrationStore? = null,
    private val now: () -> Long = { currentEpochSeconds() },
    private val onUnauthorized: suspend (AuthToken) -> Unit = {},
    /** Called with the MAL ids that have no AniList media (debug logging in prod). */
    private val onUnmatched: (MediaType, List<Int>) -> Unit = { _, _ -> },
) {

    suspend fun load(
        type: MediaType,
        userId: Int,
        forceHydration: Boolean = false,
    ): NetworkResult<List<MediaListEntryDto>> {
        val token = tokenProvider()
            ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))

        val entries = when (val result = fetch(type, token)) {
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                return result
            }
            is NetworkResult.Success -> result.value
        }

        val nowSeconds = now()
        val lastFull = hydrationStore?.lastFull(type)
        val fullHydration = forceHydration || lastFull == null ||
            nowSeconds - lastFull >= FullHydrationTtlSeconds

        if (entries.isEmpty()) {
            cache?.write(userId, type, emptyList(), ProviderId.Mal)
            if (fullHydration) hydrationStore?.markFull(type, nowSeconds)
            return NetworkResult.Success(emptyList())
        }

        val reused = if (fullHydration) emptyMap() else reusedMedia(type, userId, entries)
        // Known to have no AniList media: only a full hydration asks again.
        // Not a full hydration means lastFull != null, so the store is non-null here.
        val knownUnmatched = if (fullHydration) emptySet() else hydrationStore.unmatched(type)
        val missing = entries.map { it.malId }.filterNot { it in reused || it in knownUnmatched }
        val hydrated = if (missing.isEmpty()) {
            emptyMap()
        } else {
            when (val result = bridge.hydrateByMalIds(missing, type)) {
                is NetworkResult.Failure -> return result
                is NetworkResult.Success -> result.value
            }
        }
        val media = reused + hydrated

        val unmatchedIds = entries.map { it.malId }.filterNot { it in media }
        val matched = entries.mapNotNull { entry ->
            media[entry.malId]?.let { entry.toDto(it) }
        }
        if (unmatchedIds.isNotEmpty()) onUnmatched(type, unmatchedIds)
        hydrationStore?.saveUnmatched(type, unmatchedIds.toSet())

        cache?.write(userId, type, matched, ProviderId.Mal)
        if (fullHydration) hydrationStore?.markFull(type, nowSeconds)
        return NetworkResult.Success(matched)
    }

    suspend fun cached(type: MediaType, userId: Int): List<MediaListEntryDto>? =
        cache?.read(userId, type, ProviderId.Mal)

    /** Cached AniList media for the malIds we already know, no network. */
    private suspend fun reusedMedia(
        type: MediaType,
        userId: Int,
        entries: List<MalListEntry>,
    ): Map<Int, MediaListMediaDto> {
        val cached = cache?.read(userId, type, ProviderId.Mal).orEmpty()
        val idMap = bridge.cachedIdMap(type)
        if (cached.isEmpty() || idMap.isEmpty()) return emptyMap()
        val mediaByAniListId = cached.associateBy { it.media.id }
        return entries.mapNotNull { entry ->
            val aniListId = idMap[entry.malId] ?: return@mapNotNull null
            mediaByAniListId[aniListId]?.let { entry.malId to it.media }
        }.toMap()
    }

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
    status = malStatusToMediaListStatus(status).toApiValue(),
    score = score,
    progress = progress,
    progressVolumes = progressVolumes,
    updatedAt = parseMalUpdatedAt(updatedAt),
    startedAt = parseMalFuzzyDate(startDate),
    completedAt = parseMalFuzzyDate(finishDate),
    priority = priority,
    media = media,
)
