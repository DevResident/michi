package com.luum.michi.app.core.person.repository.dto

import com.luum.michi.app.core.media.domain.LocalDisplayNames
import com.luum.michi.app.core.media.domain.NamePick
import kotlinx.serialization.Serializable

/**
 * AniList `Character` and `Staff` share the same name/image shape. We unify
 * them into a single set of DTOs to avoid duplication.
 */
@Serializable
internal data class PersonNameDto(
    val full: String? = null,
    val first: String? = null,
    val last: String? = null,
    val userPreferred: String? = null,
    val native: String? = null,
    val alternative: List<String> = emptyList(),
) {
    val bestName: String
        get() = LocalDisplayNames.name?.let(::pickedName)
            ?: userPreferred ?: full ?: listOfNotNull(first, last).joinToString(" ").ifBlank { null } ?: ""

    /**
     * The name in a local [pick] (MAL sessions, see [LocalDisplayNames]):
     * western order ("First Last"), romaji order ("Last First") or native,
     * falling back to the other forms when AniList lacks that one.
     */
    fun pickedName(pick: NamePick): String? {
        fun String?.ok() = this?.takeIf { it.isNotBlank() }
        val western = full.ok() ?: listOfNotNull(first.ok(), last.ok()).joinToString(" ").ok()
        val romajiOrder = listOfNotNull(last.ok(), first.ok()).joinToString(" ").ok()
        return when (pick) {
            NamePick.ROMAJI_WESTERN -> western ?: userPreferred.ok() ?: native.ok()
            NamePick.ROMAJI -> romajiOrder ?: western ?: userPreferred.ok() ?: native.ok()
            NamePick.NATIVE -> native.ok() ?: western ?: userPreferred.ok()
        }
    }
}

@Serializable
internal data class PersonImageDto(
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
internal data class CharacterDto(
    val id: Int,
    val name: PersonNameDto? = null,
    val image: PersonImageDto? = null,
)

@Serializable
internal data class StaffDto(
    val id: Int,
    val name: PersonNameDto? = null,
    val image: PersonImageDto? = null,
)

@Serializable
internal data class StudioDto(
    val id: Int,
    val name: String,
    val isAnimationStudio: Boolean = false,
)
