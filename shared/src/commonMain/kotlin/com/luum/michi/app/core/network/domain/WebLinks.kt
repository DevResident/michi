package com.luum.michi.app.core.network.domain

/**
 * Every web page the app opens in the browser or shares, in one place. API
 * endpoints live with their clients ([AniListEndpoints], MalOAuthConfig,
 * AnimeThemesRepositoryImpl); this file is only for pages a person sees.
 *
 * Builders take values that are already encoded where the URL needs it.
 */
internal object WebLinks {

    object AniList {
        const val Home = "https://anilist.co"
        const val Notifications = "$Home/notifications"
        const val Settings = "$Home/settings/"
        const val SettingsAccount = "$Home/settings/account"

        /** [segment] is `anime` or `manga`. */
        fun media(segment: String, id: Int): String = "$Home/$segment/$id"
        fun character(id: Int): String = "$Home/character/$id"
        fun staff(id: Int): String = "$Home/staff/$id"
        fun studio(id: Int): String = "$Home/studio/$id"
        fun user(name: String): String = "$Home/user/$name"
        fun activity(id: Int): String = "$Home/activity/$id"

        fun forumThread(threadId: Int, commentId: Int? = null): String {
            val commentPath = if (commentId != null && commentId > 0) "/comment/$commentId" else ""
            return "$Home/forum/thread/$threadId$commentPath"
        }

        /** [segment] is `anime` or `manga`; [encodedGenre] is already percent-encoded. */
        fun genreSearch(segment: String, encodedGenre: String): String =
            "$Home/search/$segment?genres=$encodedGenre"
    }

    object Mal {
        const val Home = "https://myanimelist.net"
        const val EditProfile = "$Home/editprofile.php"
        const val AccountOptions = "$Home/editprofile.php?go=myoptions"

        /** [segment] is `anime` or `manga`. */
        fun media(segment: String, malId: Int): String = "$Home/$segment/$malId"
    }

    object AnimeThemes {
        const val Home = "https://animethemes.moe"

        fun theme(animeSlug: String, themeSlug: String): String = "$Home/anime/$animeSlug/$themeSlug"
    }

    /** The app's own repository: source, issues, releases and legal pages. */
    object Repo {
        const val Home = "https://github.com/LuumGrid/michi"
        const val NewIssue = "$Home/issues/new"
        const val LatestRelease = "$Home/releases/latest"
        const val PrivacyPolicy = "$Home/blob/main/docs/privacy.md"
        const val License = "$Home/blob/main/LICENSE"
    }

    /** Video and music services; every [query] is already percent-encoded. */
    object Media {
        fun youtubeVideo(videoId: String): String = "https://youtu.be/$videoId"
        fun dailymotionVideo(videoId: String): String = "https://www.dailymotion.com/video/$videoId"
        fun youtubeSearch(query: String): String = "https://www.youtube.com/results?search_query=$query"
        fun spotifySearch(query: String): String = "https://open.spotify.com/search/$query"
        fun youtubeMusicSearch(query: String): String = "https://music.youtube.com/search?q=$query"
        fun appleMusicSearch(query: String): String = "https://music.apple.com/search?term=$query"
    }
}
