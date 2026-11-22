package com.luum.michi.app.core.mal.domain

/**
 * The viewer's MAL list entry as MAL returns it (detail GET or PATCH response).
 * Dates stay raw because MAL allows partial dates (`YYYY`, `YYYY-MM`); the
 * editor model uses millis and normalizes them to day 1 for display.
 */
internal data class MalEntryStatus(
    val status: String?,
    val score: Double,
    val progress: Int,
    val progressVolumes: Int?,
    val isRewatching: Boolean,
    val isRereading: Boolean,
    val updatedAt: String?,
    val startDate: String?,
    val finishDate: String?,
    val priority: Int?,
    val numTimesRewatched: Int,
    val numTimesReread: Int,
    val comments: String?,
)
