package com.luum.michi.app.core.auth.domain

import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.ScoreFormat

/**
 * What the active account's provider supports. The UI hides an unsupported
 * feature instead of disabling it. AniList declares every capability; MAL
 * declares only what it actually has. The catalog (Discover, detail, search,
 * calendar) is always AniList and is not gated here.
 */
internal data class ProviderCapabilities(
    /** null = the user's own format (AniList); a value = fixed by the provider. */
    val fixedScoreFormat: ScoreFormat? = null,
    val advancedScores: Boolean,
    val privateEntries: Boolean,
    val hiddenFromStatusLists: Boolean,
    val repeatCount: Boolean,
    val priority: Boolean,
    val notes: Boolean,
    /** REPEATING as its own list status (MAL uses a flag instead). */
    val repeatingStatus: Boolean,
    val favourites: Boolean,
    val notifications: Boolean,
    /** Following / followers / reviews. */
    val social: Boolean,
    val profileStatistics: Boolean,
    /** Settings synced with the account (AniList `UpdateUser`). */
    val remoteSettings: Boolean,
) {
    companion object {
        val AniList = ProviderCapabilities(
            advancedScores = true,
            privateEntries = true,
            hiddenFromStatusLists = true,
            repeatCount = true,
            priority = true,
            notes = true,
            repeatingStatus = true,
            favourites = true,
            notifications = true,
            social = true,
            profileStatistics = true,
            remoteSettings = true,
        )

        /**
         * MyAnimeList. Scores are integers 0–10, entries carry comments and a
         * priority (0–2), rewatches are a flag rather than a status, and the
         * account has none of AniList's favourites, feed or profile extras.
         */
        val Mal = ProviderCapabilities(
            fixedScoreFormat = ScoreFormat.POINT_10,
            advancedScores = false,
            privateEntries = false,
            hiddenFromStatusLists = false,
            repeatCount = true,
            priority = true,
            notes = true,
            repeatingStatus = false,
            favourites = false,
            notifications = false,
            social = false,
            profileStatistics = false,
            remoteSettings = false,
        )
    }
}

/** The format the editor/lists/stats must use: the provider's fixed one wins. */
internal fun ProviderCapabilities.effectiveScoreFormat(userFormat: ScoreFormat): ScoreFormat =
    fixedScoreFormat ?: userFormat

/**
 * The statuses the editor offers, in the same order it has always shown them
 * (`MediaListStatus.entries`), minus REPEATING when the provider has no such
 * status.
 */
internal fun ProviderCapabilities.editableStatuses(): List<MediaListStatus> =
    MediaListStatus.entries.filter { it != MediaListStatus.REPEATING || repeatingStatus }
