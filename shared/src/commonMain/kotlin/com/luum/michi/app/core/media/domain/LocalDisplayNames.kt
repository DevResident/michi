package com.luum.michi.app.core.media.domain

import kotlin.concurrent.Volatile

/** Which AniList title variant to show when the app (not AniList) decides. */
internal enum class TitlePick { ROMAJI, ENGLISH, NATIVE }

/** Which staff/character name form to show when the app (not AniList) decides. */
internal enum class NamePick { ROMAJI_WESTERN, ROMAJI, NATIVE }

/**
 * Client-side title and name language. With an AniList account, AniList
 * already fills `userPreferred` from the account settings, so both stay null
 * and `userPreferred` wins. A MAL session reads AniList anonymously (where
 * `userPreferred` is always romaji), so the app applies the local preference
 * itself from the romaji/english/native fields every query already asks for.
 *
 * Root sets these from the settings holder; the title/name formatters read
 * them, so lists, detail, calendar, search and favourites all follow at once.
 */
internal object LocalDisplayNames {
    @Volatile
    var title: TitlePick? = null

    @Volatile
    var name: NamePick? = null
}
