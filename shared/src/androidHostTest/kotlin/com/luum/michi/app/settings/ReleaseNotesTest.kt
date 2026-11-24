package com.luum.michi.app.settings

import com.luum.michi.app.core.language.domain.EnglishStrings
import com.luum.michi.app.core.language.domain.SpanishStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesTest {

    @Test
    fun bothLanguagesShipTheSameVersionsAndSections() {
        val english = EnglishStrings.releaseNotes
        val spanish = SpanishStrings.releaseNotes

        assertTrue(english.isNotEmpty())
        assertEquals(
            english.map { it.version },
            spanish.map { it.version },
            "the version list never depends on the language",
        )
        english.zip(spanish).forEach { (en, es) ->
            assertEquals(en.sections.size, es.sections.size)
            assertTrue(en.sections.zip(es.sections).all { (a, b) -> a.items.size == b.items.size })
        }
    }

    @Test
    fun theNewestVersionComesFirstAndHasContent() {
        val notes = EnglishStrings.releaseNotes

        assertEquals(listOf("1.2", "1.1"), notes.map { it.version })
        assertTrue(notes.first().sections.isNotEmpty())
        assertTrue(notes.first().sections.all { it.title.isNotBlank() && it.items.isNotEmpty() })
        assertTrue(notes.first().sections.flatMap { it.items }.all { it.isNotBlank() })
    }
}
