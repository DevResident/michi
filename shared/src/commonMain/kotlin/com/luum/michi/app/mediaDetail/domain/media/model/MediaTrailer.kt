package com.luum.michi.app.mediaDetail.domain.media.model

import com.luum.michi.app.core.network.domain.WebLinks

internal fun MediaTrailer.videoUrl(): String? = when {
    site.equals("youtube", ignoreCase = true) -> WebLinks.Media.youtubeVideo(videoId)
    site.equals("dailymotion", ignoreCase = true) -> WebLinks.Media.dailymotionVideo(videoId)
    site.isNullOrBlank() && videoId.startsWith("http") -> videoId
    else -> null
}
