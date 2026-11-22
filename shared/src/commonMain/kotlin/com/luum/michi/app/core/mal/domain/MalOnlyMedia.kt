package com.luum.michi.app.core.mal.domain

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId

/*
 * "MAL-only" entries: works on the viewer's MAL list that have no AniList
 * media. The list still shows them, built from MAL's own node data, under a
 * synthetic negative id so they never collide with an AniList id: `-malId` for
 * anime, `-(MangaOffset + malId)` for manga, so the id alone says which MAL
 * record it is ([malRefOfMalOnly]).
 *
 * A negative id is not an AniList id: it must never reach AniList, the id
 * bridge, the detail page or favourites. Reads and writes of these entries go
 * straight to MAL. [isMalOnlyMediaId] is the single check every boundary uses.
 */

/** MAL ids are far below this (hundreds of thousands); keeps the two catalogs apart. */
private const val MangaOffset = 1_000_000_000

/** The synthetic list id of a MAL-only work. */
internal fun malOnlyMediaId(malId: Int, type: MediaType): Int =
    if (type == MediaType.MANGA) -(MangaOffset + malId) else -malId

/** True for a synthetic MAL-only id (any id that is not a valid AniList id). */
internal fun isMalOnlyMediaId(id: Int): Boolean = id <= 0

/** True for an AniList-provider ref that carries a synthetic MAL-only id. */
internal fun EntityRef.isMalOnlyAniListRef(): Boolean =
    provider == ProviderId.AniList && id.toIntOrNull()?.let(::isMalOnlyMediaId) == true

/** The MAL record behind a synthetic MAL-only id. */
internal fun malRefOfMalOnly(id: Int): MalRef {
    require(isMalOnlyMediaId(id) && id != 0) { "Not a MAL-only id: $id" }
    val positive = -id
    return if (positive > MangaOffset) {
        MalRef(malId = positive - MangaOffset, type = MediaType.MANGA)
    } else {
        MalRef(malId = positive, type = MediaType.ANIME)
    }
}

/** The MAL id behind a synthetic MAL-only id. */
internal fun malIdOfMalOnly(id: Int): Int = malRefOfMalOnly(id).malId

/** The work's public page on myanimelist.net. */
internal fun malPageUrl(type: MediaType, malId: Int): String {
    val path = if (type == MediaType.MANGA) "manga" else "anime"
    return "https://myanimelist.net/$path/$malId"
}

/** MAL `media_type` in AniList's format vocabulary (what the list mappers parse). */
internal fun malMediaTypeToFormat(mediaType: String?): String? = when (mediaType?.lowercase()) {
    "tv" -> "TV"
    "ova" -> "OVA"
    "movie" -> "MOVIE"
    // AniList files TV specials under SPECIAL.
    "special", "tv_special" -> "SPECIAL"
    "ona" -> "ONA"
    "music" -> "MUSIC"
    "manga", "manhwa", "manhua", "doujinshi", "oel" -> "MANGA"
    "novel", "light_novel" -> "NOVEL"
    "one_shot" -> "ONE_SHOT"
    else -> null
}

/** MAL work `status` in AniList's vocabulary (MediaWorkStatus). */
internal fun malWorkStatusToMediaStatus(status: String?): String? = when (status?.lowercase()) {
    "finished_airing", "finished" -> "FINISHED"
    "currently_airing", "currently_publishing" -> "RELEASING"
    "not_yet_aired", "not_yet_published" -> "NOT_YET_RELEASED"
    "on_hiatus" -> "HIATUS"
    "discontinued" -> "CANCELLED"
    else -> null
}

/** MAL `start_season.season` in AniList's MediaSeason vocabulary. */
internal fun malSeasonToMediaSeason(season: String?): String? = when (season?.lowercase()) {
    "winter" -> "WINTER"
    "spring" -> "SPRING"
    "summer" -> "SUMMER"
    "fall" -> "FALL"
    else -> null
}
