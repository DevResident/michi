package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.mal.repository.parseMalFuzzyDate
import com.luum.michi.app.core.mal.repository.parseMalUpdatedAt
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.bestTitle
import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.media.repository.toCoverSizes
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorSnapshot
import com.luum.michi.app.core.medialist.domain.MirrorSnapshotEntry
import com.luum.michi.app.core.medialist.domain.MirrorTranslator
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseMediaListStatus
import com.luum.michi.app.core.model.CoverSizes
import com.luum.michi.app.core.network.domain.AniListGraphQLClient
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.core.session.domain.MirrorTokenSource
import com.luum.michi.app.mediaList.repository.MediaListEntryDto
import com.luum.michi.app.mediaList.repository.fetchMediaListCollection
import com.luum.michi.app.mediaList.repository.mal.MalListLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The destination's own totals; the differ only caps with positive values. */
internal data class MirrorTargetTotals(
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
)

/**
 * Fresh snapshots for "Sync now". The primary is never read from the UI cache:
 * a web edit on AniList/MAL must be visible before we decide what to copy.
 */
internal class MirrorSnapshotReader(
    private val sessionProvider: () -> ProviderId,
    private val sessionUserId: () -> Int?,
    private val sessionScoreFormat: () -> ScoreFormat,
    private val sessionAniListClient: AniListGraphQLClient,
    private val sessionMalLoader: MalListLoader,
    private val linkedAccounts: LinkedAccountManager,
    private val linkedAniListClient: AniListGraphQLClient,
    private val mirrorTokens: MirrorTokenSource,
    private val malHttp: MalHttp,
    private val bridge: MediaIdBridge,
    private val onUnauthorized: suspend (AuthToken) -> Unit,
) {
    suspend fun readPrimary(): NetworkResult<MirrorSnapshot> = coroutineScope {
        val provider = sessionProvider()
        val userId = sessionUserId()
            ?: return@coroutineScope NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No authenticated viewer id")),
            )
        if (provider != ProviderId.AniList && provider != ProviderId.Mal) {
            return@coroutineScope NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("Unsupported session provider")),
            )
        }
        val scoreFormat = if (provider == ProviderId.AniList) {
            sessionScoreFormat()
        } else {
            ScoreFormat.POINT_10
        }
        // Both media types are independent reads. They run together, but the
        // results are combined in MediaType.entries order: the final snapshot —
        // and therefore the diff — never depends on which one answers first.
        val results = MediaType.entries.map { type ->
            async {
                when (provider) {
                    ProviderId.AniList -> sessionAniListClient
                        .fetchMediaListCollection(userId, type)
                        .mapEntriesToMirror(
                            provider = ProviderId.AniList,
                            type = type,
                            scoreFormat = scoreFormat,
                            resolveMissingMalId = true,
                        )
                    else -> sessionMalLoader
                        .refresh(type, userId)
                        .mapEntriesToMirror(
                            provider = ProviderId.Mal,
                            type = type,
                            scoreFormat = ScoreFormat.POINT_10,
                            resolveMissingMalId = false,
                        )
                }
            }
        }.awaitAll()
        val entries = mutableListOf<MirrorSnapshotEntry>()
        for (result in results) {
            when (result) {
                is NetworkResult.Failure -> return@coroutineScope result
                is NetworkResult.Success -> entries += result.value
            }
        }
        NetworkResult.Success(MirrorSnapshot(provider, entries))
    }

    suspend fun readLinked(): NetworkResult<MirrorSnapshot> = coroutineScope {
        val linked = linkedAccounts.linked.value
            ?: return@coroutineScope NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No linked account")),
            )
        val results = when (linked.provider) {
            ProviderId.AniList -> MediaType.entries.map { type ->
                async {
                    linkedAniListClient
                        .fetchMediaListCollection(linked.viewer.id, type)
                        .mapEntriesToMirror(
                            provider = ProviderId.AniList,
                            type = type,
                            scoreFormat = linked.viewer.scoreFormat,
                            resolveMissingMalId = false,
                        )
                }
            }.awaitAll()
            ProviderId.Mal -> {
                val token = mirrorTokens.mirrorToken(ProviderId.Mal)
                    ?: return@coroutineScope NetworkResult.Failure(NetworkError.Unauthorized(null))
                val pages = MediaType.entries.map { type ->
                    async {
                        when (val result = when (type) {
                            MediaType.ANIME -> malHttp.loadAnimeList(token)
                            MediaType.MANGA -> malHttp.loadMangaList(token)
                        }) {
                            is NetworkResult.Failure -> result
                            is NetworkResult.Success -> NetworkResult.Success(
                                result.value.map { it.toMirrorEntry(type) },
                            )
                        }
                    }
                }.awaitAll()
                // Both pages can answer 401 at once; notify the session only
                // once for this read, after both results are in.
                if (pages.any { (it as? NetworkResult.Failure)?.error is NetworkError.Unauthorized }) {
                    onUnauthorized(token)
                }
                pages
            }
            else -> return@coroutineScope NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("Unsupported linked provider")),
            )
        }
        val entries = mutableListOf<MirrorSnapshotEntry>()
        for (result in results) {
            when (result) {
                is NetworkResult.Failure -> return@coroutineScope result
                is NetworkResult.Success -> entries += result.value
            }
        }
        NetworkResult.Success(MirrorSnapshot(linked.provider, entries))
    }

    private suspend fun NetworkResult<List<MediaListEntryDto>>.mapEntriesToMirror(
        provider: ProviderId,
        type: MediaType,
        scoreFormat: ScoreFormat,
        resolveMissingMalId: Boolean,
    ): NetworkResult<List<MirrorSnapshotEntry>> = when (this) {
        is NetworkResult.Failure -> this
        is NetworkResult.Success -> NetworkResult.Success(
            value.toMirrorEntries(
                provider = provider,
                type = type,
                scoreFormat = scoreFormat,
                resolveMissingMalId = resolveMissingMalId,
            ),
        )
    }

    /**
     * Fallback for the rows whose target snapshot is missing the work total.
     * The MAL list already sends the requested node totals, so this normally
     * runs with an empty list and makes no request. A failure leaves the total
     * unknown (no cap), never fails the whole preview.
     */
    suspend fun readMalTotals(
        requests: List<Pair<MediaType, Int>>,
    ): Map<Pair<MediaType, Int>, MirrorTargetTotals> {
        val distinct = requests.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val token = mirrorTokens.mirrorToken(ProviderId.Mal) ?: return emptyMap()
        val result = mutableMapOf<Pair<MediaType, Int>, MirrorTargetTotals>()
        for ((type, malId) in distinct) {
            when (val entry = malHttp.fetchWorkEntry(token, MalRef(malId, type))) {
                is NetworkResult.Success -> {
                    result[type to malId] = MirrorTargetTotals(
                        episodes = entry.value.episodes,
                        chapters = entry.value.chapters,
                        volumes = entry.value.volumes,
                    )
                }
                is NetworkResult.Failure -> {
                    if (entry.error is NetworkError.Unauthorized) {
                        onUnauthorized(token)
                        return result
                    }
                    // Unknown total: leave the row uncapped.
                }
            }
        }
        return result
    }

    private suspend fun List<MediaListEntryDto>.toMirrorEntries(
        provider: ProviderId,
        type: MediaType,
        scoreFormat: ScoreFormat,
        resolveMissingMalId: Boolean,
    ): List<MirrorSnapshotEntry> = mapNotNull { dto ->
        val malId = dto.media.idMal ?: if (resolveMissingMalId) {
            when (val resolved = bridge.resolveMal(dto.media.id)) {
                is NetworkResult.Success -> resolved.value?.malId
                is NetworkResult.Failure -> null
            }
        } else {
            null
        }
        val status = parseMediaListStatus(dto.status)
        val rewatching = dto.isRewatching == true || status == MediaListStatus.REPEATING
        MirrorSnapshotEntry(
            media = MirrorMediaSummary(
                aniListId = dto.media.id,
                malId = malId,
                type = type,
                title = dto.media.title?.bestTitle() ?: "AniList #${dto.media.id}",
                cover = dto.media.coverImage?.toCoverSizes(),
                totalEpisodes = dto.media.episodes,
                totalChapters = dto.media.chapters,
                totalVolumes = dto.media.volumes,
            ),
            entry = MirrorEntry(
                sourceProvider = provider,
                aniListId = dto.media.id,
                malId = malId,
                type = type,
                status = if (rewatching) MediaListStatus.REPEATING else status,
                progress = dto.progress,
                progressVolumes = dto.progressVolumes,
                score = dto.score.toFloat(),
                scoreFormat = scoreFormat,
                repeat = dto.repeat ?: 0,
                priority = dto.priority ?: 0,
                notes = dto.notes.orEmpty(),
                isPrivate = dto.isPrivate == true,
                isRewatching = rewatching,
                startedAt = dto.startedAt.toMirrorDate(),
                completedAt = dto.completedAt.toMirrorDate(),
                updatedAt = dto.updatedAt,
            ),
        )
    }

    private fun MalListEntry.toMirrorEntry(type: MediaType): MirrorSnapshotEntry {
        val status = malStatusToMediaListStatus(status)
        val rewatching = if (type == MediaType.MANGA) isRereading else isRewatching
        val title = node.title?.takeIf { it.isNotBlank() } ?: "MAL #$malId"
        return MirrorSnapshotEntry(
            media = MirrorMediaSummary(
                aniListId = 0,
                malId = malId,
                type = type,
                title = title,
                cover = CoverSizes(
                    medium = node.pictureMedium,
                    large = node.pictureLarge,
                ),
                totalEpisodes = if (type == MediaType.ANIME) node.episodes else null,
                totalChapters = if (type == MediaType.MANGA) node.chapters else null,
                totalVolumes = if (type == MediaType.MANGA) node.volumes else null,
            ),
            entry = MirrorEntry(
                sourceProvider = ProviderId.Mal,
                aniListId = 0,
                malId = malId,
                type = type,
                status = if (rewatching) MediaListStatus.REPEATING else status,
                progress = progress,
                progressVolumes = progressVolumes,
                score = score.toFloat(),
                scoreFormat = ScoreFormat.POINT_10,
                repeat = if (type == MediaType.MANGA) numTimesReread else numTimesRewatched,
                priority = priority ?: 0,
                notes = comments.orEmpty(),
                isPrivate = false,
                isRewatching = rewatching,
                startedAt = parseMalFuzzyDate(startDate)?.toMirrorDate(),
                completedAt = parseMalFuzzyDate(finishDate)?.toMirrorDate(),
                updatedAt = parseMalUpdatedAt(updatedAt),
            ),
        )
    }

    private fun FuzzyDateDto?.toMirrorDate(): MirrorDate? {
        val year = this?.year ?: return null
        return MirrorDate(year = year, month = month, day = day)
    }
}
