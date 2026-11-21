package com.luum.michi.app.core.media.domain

import kotlin.jvm.JvmInline

/**
 * Which provider an entity id belongs to. A text-backed value class instead of
 * an enum so adding a provider never requires touching this type or every
 * `when` over it. [AniList] owns the catalog; [Mal] is the second login provider.
 */
@JvmInline
internal value class ProviderId(val value: String) {
    companion object {
        val AniList = ProviderId("anilist")
        val Mal = ProviderId("mal")
    }
}
