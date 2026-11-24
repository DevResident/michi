package com.luum.michi.app.mediaDetail.domain.media.model

import com.luum.michi.app.core.network.domain.WebLinks
import io.ktor.http.encodeURLParameter

/** Music services offered as search links for a theme song: no extra API, the query is title + artists. */
internal enum class MusicService(val label: String, val brandHex: String) {
    SPOTIFY("Spotify", "#1DB954"),
    YOUTUBE_MUSIC("YouTube Music", "#FF0033"),
    APPLE_MUSIC("Apple Music", "#FA2D48");

    fun searchUrl(query: String): String {
        val q = query.encodeURLParameter(spaceToPlus = false)
        return when (this) {
            SPOTIFY -> WebLinks.Media.spotifySearch(q)
            YOUTUBE_MUSIC -> WebLinks.Media.youtubeMusicSearch(q)
            APPLE_MUSIC -> WebLinks.Media.appleMusicSearch(q)
        }
    }
}

internal fun AnimeTheme.searchQuery(): String = (listOf(title) + artists).joinToString(" ")

/** YouTube search for the theme's video: anime name + label + song ("Sousou no Frieren OP1 Yuusha"). */
internal fun AnimeTheme.videoSearchUrl(): String =
    WebLinks.Media.youtubeSearch("$animeName $label $title".encodeURLParameter(spaceToPlus = false))
