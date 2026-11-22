package com.luum.michi.app.core.media.domain

/** One MyAnimeList record: its MAL id and which catalog it belongs to. */
internal data class MalRef(
    val malId: Int,
    val type: MediaType,
)
