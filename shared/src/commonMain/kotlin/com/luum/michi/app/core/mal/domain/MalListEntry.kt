package com.luum.michi.app.core.mal.domain

/**
 * One MAL list row before AniList hydration. Dates stay as MAL sends them
 * (`YYYY`, `YYYY-MM`, `YYYY-MM-DD`, ISO 8601); the list repository parses them.
 * [node] is MAL's own data about the work: only used when AniList has no media
 * for [malId] (a MAL-only entry).
 */
internal data class MalListEntry(
    val malId: Int,
    val status: String?,
    val score: Double,
    val progress: Int,
    val progressVolumes: Int?,
    val updatedAt: String?,
    val startDate: String?,
    val finishDate: String?,
    val priority: Int?,
    val node: MalNodeInfo = MalNodeInfo(),
)

/** MAL's catalog fields for a list row, as MAL sends them. */
internal data class MalNodeInfo(
    val title: String? = null,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val pictureMedium: String? = null,
    val pictureLarge: String? = null,
    /** MAL `media_type` (tv, movie, manga, light_novel, …). */
    val mediaType: String? = null,
    /** MAL work `status` (finished_airing, currently_publishing, …). */
    val status: String? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val startYear: Int? = null,
    val startSeason: String? = null,
    /** MAL `nsfw`: "white", "gray" or "black". */
    val nsfw: String? = null,
)
