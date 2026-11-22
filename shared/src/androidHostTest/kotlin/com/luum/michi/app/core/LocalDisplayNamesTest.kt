package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.LocalDisplayNames
import com.luum.michi.app.core.media.domain.NamePick
import com.luum.michi.app.core.media.domain.TitlePick
import com.luum.michi.app.core.media.repository.bestTitle
import com.luum.michi.app.core.media.repository.dto.MediaTitleDto
import com.luum.michi.app.core.person.repository.dto.PersonNameDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDisplayNamesTest {

    private val title = MediaTitleDto(
        romaji = "Sousou no Frieren",
        english = "Frieren: Beyond Journey's End",
        native = "葬送のフリーレン",
        userPreferred = "Sousou no Frieren",
    )
    private val name = PersonNameDto(
        full = "Atsumi Tanezaki",
        first = "Atsumi",
        last = "Tanezaki",
        native = "種﨑敦美",
        userPreferred = "Atsumi Tanezaki",
    )

    @AfterTest
    fun reset() {
        LocalDisplayNames.title = null
        LocalDisplayNames.name = null
    }

    @Test
    fun withoutALocalPickAniListsUserPreferredWins() {
        assertEquals("Sousou no Frieren", title.bestTitle())
        assertEquals("Atsumi Tanezaki", name.bestName)
    }

    @Test
    fun aLocalTitlePickChoosesThatVariant() {
        LocalDisplayNames.title = TitlePick.ENGLISH
        assertEquals("Frieren: Beyond Journey's End", title.bestTitle())
        LocalDisplayNames.title = TitlePick.NATIVE
        assertEquals("葬送のフリーレン", title.bestTitle())
        LocalDisplayNames.title = TitlePick.ROMAJI
        assertEquals("Sousou no Frieren", title.bestTitle())
    }

    @Test
    fun aMissingVariantFallsBackToRomaji() {
        LocalDisplayNames.title = TitlePick.ENGLISH
        assertEquals("Only Romaji", MediaTitleDto(romaji = "Only Romaji", english = " ").bestTitle())
    }

    @Test
    fun aLocalNamePickChoosesTheOrderOrScript() {
        LocalDisplayNames.name = NamePick.ROMAJI_WESTERN
        assertEquals("Atsumi Tanezaki", name.bestName)
        LocalDisplayNames.name = NamePick.ROMAJI
        assertEquals("Tanezaki Atsumi", name.bestName)
        LocalDisplayNames.name = NamePick.NATIVE
        assertEquals("種﨑敦美", name.bestName)
        assertEquals("Atsumi Tanezaki", name.copy(native = null).bestName)
    }
}
