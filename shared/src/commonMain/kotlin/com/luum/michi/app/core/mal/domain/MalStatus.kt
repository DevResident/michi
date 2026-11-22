package com.luum.michi.app.core.mal.domain

import com.luum.michi.app.core.medialist.domain.MediaListStatus

/** MAL statuses collapse onto AniList's vocabulary; rewatches are a flag, not a section. */
internal fun malStatusToMediaListStatus(status: String?): MediaListStatus = when (status?.lowercase()) {
    "watching", "reading" -> MediaListStatus.CURRENT
    "completed" -> MediaListStatus.COMPLETED
    "on_hold" -> MediaListStatus.PAUSED
    "dropped" -> MediaListStatus.DROPPED
    "plan_to_watch", "plan_to_read" -> MediaListStatus.PLANNING
    // Unknown/missing status keeps the same fallback the AniList mapper uses.
    else -> MediaListStatus.CURRENT
}

/** AniList status → MAL status; null when MAL has no equivalent (REPEATING). */
internal fun MediaListStatus.toMalStatus(isManga: Boolean): String? = when (this) {
    MediaListStatus.CURRENT -> if (isManga) "reading" else "watching"
    MediaListStatus.COMPLETED -> "completed"
    MediaListStatus.PAUSED -> "on_hold"
    MediaListStatus.DROPPED -> "dropped"
    MediaListStatus.PLANNING -> if (isManga) "plan_to_read" else "plan_to_watch"
    MediaListStatus.REPEATING -> null
}
