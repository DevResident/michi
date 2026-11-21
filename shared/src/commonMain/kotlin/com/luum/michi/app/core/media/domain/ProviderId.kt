package com.luum.michi.app.core.media.domain

import kotlin.jvm.JvmInline

/**
 * Which provider an entity id belongs to. A text-backed value class instead of
 * an enum so adding a provider never requires touching this type or every
 * `when` over it; [AniList] is the only provider today.
 */
@JvmInline
internal value class ProviderId(val value: String) {
    companion object {
        val AniList = ProviderId("anilist")
    }
}
