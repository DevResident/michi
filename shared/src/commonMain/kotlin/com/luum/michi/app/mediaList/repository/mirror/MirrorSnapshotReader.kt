package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.mal.domain.MalListEntry
import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import com.luum.michi.app.core.mal.repository.MalHttp
import com.luum.michi.app.core.mal.repository.parseMalFuzzyDate
import com.luum.michi.app.core.mal.repository.parseMalUpdatedAt
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.media.repository.bestTitle
import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorSnapshot
import com.luum.michi.app.core.medialist.domain.MirrorSnapshotEntry
import com.luum.michi.app.core.medialist.domain.MirrorTranslator
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseMediaListStatus
import com.luum.michi.app.core.network.domain.AniListGraphQLClient
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.mediaList.repository.MediaListEntryDto
import com.luum.michi.app.mediaList.repository.fetchMediaListCollection
import com.luum.michi.app.mediaList.repository.mal.MalListLoader

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
    private val malHttp: MalHttp,
    private val bridge: MediaIdBridge,
) {
    suspend fun readPrimary(): NetworkResult<MirrorSnapshot> {
        val provider = sessionProvider()
        val userId = sessionUserId()
            ?: return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No authenticated viewer id")),
            )
        val entries = mutableListOf<MirrorSnapshotEntry>()
        for (type in MediaType.entries) {
            when (provider) {
                ProviderId.AniList -> {
                    val result = sessionAniListClient.fetchMediaListCollection(userId, type)
                    when (result) {
                        is NetworkResult.Failure -> return result
                        is NetworkResult.Success -> entries += result.value.toMirrorEntries(
                            provider = ProviderId.AniList,
                            type = type,
                            scoreFormat = sessionScoreFormat(),
                            resolveMissingMalId = true,
                        )
                    }
                }
                ProviderId.Mal -> {
                    val result = sessionMalLoader.refresh(type, userId)
                    when (result) {
                        is NetworkResult.Failure -> return result
                        is NetworkResult.Success -> entries += result.value.toMirrorEntries(
                            provider = ProviderId.Mal,
                            type = type,
                            scoreFormat = ScoreFormat.POINT_10,
                            resolveMissingMalId = false,
                        )
                    }
                }
                else -> return NetworkResult.Failure(
                    NetworkError.Unknown(IllegalStateException("Unsupported session provider")),
                )
            }
        }
        return NetworkResult.Success(MirrorSnapshot(provider, entries))
    }

    suspend fun readLinked(): NetworkResult<MirrorSnapshot> {
        val linked = linkedAccounts.linked.value
            ?: return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No linked account")),
            )
        val entries = mutableListOf<MirrorSnapshotEntry>()
        when (linked.provider) {
            ProviderId.AniList -> {
                for (type in MediaType.entries) {
                    val result = linkedAniListClient.fetchMediaListCollection(
                        linked.viewer.id,
                        type,
                    )
                    when (result) {
                        is NetworkResult.Failure -> return result
                        is NetworkResult.Success -> entries += result.value.toMirrorEntries(
                            provider = ProviderId.AniList,
                            type = type,
                            scoreFormat = linked.viewer.scoreFormat,
                            resolveMissingMalId = false,
                        )
                    }
                }
            }
            ProviderId.Mal -> {
                val token = linkedAccounts.validToken(ProviderId.Mal)
                    ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
                for (type in MediaType.entries) {
                    val result = when (type) {
                        MediaType.ANIME -> malHttp.loadAnimeList(token)
                        MediaType.MANGA -> malHttp.loadMangaList(token)
                    }
                    when (result) {
                        is NetworkResult.Failure -> {
                            if (result.error is NetworkError.Unauthorized) {
                                linkedAccounts.onUnauthorized(token)
                            }
                            return result
                        }
                        is NetworkResult.Success -> entries += result.value.map {
                            it.toMirrorEntry(type)
                        }
                    }
                }
            }
            else -> return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("Unsupported linked provider")),
            )
        }
        return NetworkResult.Success(MirrorSnapshot(linked.provider, entries))
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
                coverUrl = dto.media.coverImage?.large ?: dto.media.coverImage?.medium,
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
                coverUrl = node.pictureLarge ?: node.pictureMedium,
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
