package com.luum.michi.app.core.mal.domain

/**
 * A partial MAL list-status PATCH. Only non-null fields are sent; an empty
 * [startDate]/[finishDate] is a deliberate clear (MAL accepts a blank date).
 */
internal data class MalEntryUpdate(
    val status: String? = null,
    val score: Int? = null,
    val progress: Int? = null,
    val progressVolumes: Int? = null,
    val priority: Int? = null,
    val repeat: Int? = null,
    val comments: String? = null,
    val startDate: String? = null,
    val finishDate: String? = null,
    /** Mirror-only: the editor leaves it null so MAL preserves its own flag. */
    val isRewatching: Boolean? = null,
)
