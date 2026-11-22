package com.luum.michi.app.core.media.repository.dto

import kotlinx.serialization.Serializable

/**
 * The exact `media { … }` block both list sources share: the AniList user-list
 * query and the MAL-id bridge. Keeping it in one constant guarantees the two
 * queries never drift; the bridge adds `idMal` explicitly on top.
 */
internal const val MediaListMediaFields = """
          id
          format
          status
          episodes
          chapters
          volumes
          averageScore
          popularity
          favourites
          trending
          genres
          season
          seasonYear
          startDate { year month day }
          title { romaji english native userPreferred }
          isAdult
          coverImage { extraLarge large medium color }
          nextAiringEpisode { episode airingAt }
"""

/**
 * The media shape a list card needs. It is deliberately narrower than
 * [MediaDto]: detail-only fields never enter the list cache.
 */
@Serializable
internal data class MediaListMediaDto(
    val id: Int,
    /** Only the bridge fills this; the AniList list query does not ask for it. */
    val idMal: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val trending: Int? = null,
    val genres: List<String>? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDateDto? = null,
    val title: MediaTitleDto? = null,
    val isAdult: Boolean? = null,
    val coverImage: MediaCoverImageDto? = null,
    val nextAiringEpisode: MediaNextAiringEpisodeDto? = null,
)
