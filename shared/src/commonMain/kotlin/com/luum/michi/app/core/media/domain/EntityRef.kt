package com.luum.michi.app.core.media.domain

/**
 * A provider-neutral reference to an entity: the same numeric id under two
 * providers is a different entity. Navigation, cache keys and list writes carry
 * this reference; domain models keep their `Int` id until 11.1b.
 */
internal data class EntityRef(val provider: ProviderId, val id: String)

/** An AniList entity reference. */
internal fun aniListRef(id: Int) = EntityRef(ProviderId.AniList, id.toString())

/**
 * AniList adapter boundary: fails loudly if a ref from another provider
 * arrives, so an unmigrated call site can never silently use a foreign id.
 */
internal fun EntityRef.requireAniListId(): Int {
    require(provider == ProviderId.AniList) { "Not an AniList ref: $this" }
    return id.toInt()
}
