package com.luum.michi.app.core.navigation.domain

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType

internal sealed interface DetailDestination {
    /**
     * A media detail. [typeHint] is what the opener already knows (a list tab, a
     * favourites category, a deep link); null when it does not know. It is only a
     * hint for the holder, so it never takes part in equality: `Media(ref, MANGA)`
     * and `Media(ref)` are the same destination (no duplicate on top of the stack).
     * The reference, not just the id, identifies the entity.
     */
    data class Media(val ref: EntityRef, val typeHint: MediaType? = null) : DetailDestination {
        override fun equals(other: Any?): Boolean = other is Media && other.ref == ref
        override fun hashCode(): Int = ref.hashCode()
    }
    data class Character(val ref: EntityRef) : DetailDestination
    data class Staff(val ref: EntityRef) : DetailDestination
    data class Studio(val ref: EntityRef) : DetailDestination

    /**
     * Works of one genre. [type] is the catalog the page opens on; the screen
     * offers an Anime/Manga switch, so the destination only seeds it.
     */
    data class Genre(val name: String, val type: MediaType) : DetailDestination

    /**
     * "View all" for a Dashboard rail. Core must not import `discover`, so the
     * rail travels as its enum name ([kindId]) and the host resolves it to the
     * shared catalog filter + rail title.
     */
    data class Catalog(val kindId: String) : DetailDestination

    /**
     * "View all" of the viewer's favourites of one category. Core must not
     * import `profile`, so the category travels as its enum name; the host
     * resolves it to the paginated favourites grid and its toolbar title.
     */
    data class ProfileFavorites(val categoryId: String) : DetailDestination

    /**
     * The viewer's statistics page, seeded on one catalog type (the header
     * counter the user tapped). Data comes from the profile query, so this page
     * costs no request of its own.
     */
    data class ProfileStatistics(val type: MediaType) : DetailDestination

    /**
     * The viewer's following or followers list. Core must not import `profile`,
     * so the kind travels as its enum name.
     */
    data class ProfileFollows(val kindId: String) : DetailDestination

    /** The viewer's reviews. Tapping one opens the reviewed media's detail. */
    data object ProfileReviews : DetailDestination

    /**
     * "View all" list reached from a detail page: the rail shows the first
     * items, this screen shows the whole (paginated) list. [parentRef] keys
     * the source so `Root` can read the right holder.
     */
    data class DetailList(val kind: DetailListKind, val parentRef: EntityRef) : DetailDestination
}

/** Which section a [DetailDestination.DetailList] renders. */
internal enum class DetailListKind {
    MEDIA_CHARACTERS,
    MEDIA_STAFF,
    MEDIA_RELATED,
    MEDIA_RECOMMENDATIONS,
    CHARACTER_MEDIA,
    STAFF_MEDIA,
    STAFF_CHARACTERS,
}
