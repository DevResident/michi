package com.luum.michi.app.core.mal.domain

/**
 * One MAL list row before AniList hydration. Dates stay as MAL sends them
 * (`YYYY`, `YYYY-MM`, `YYYY-MM-DD`, ISO 8601); the list repository parses them.
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
)
