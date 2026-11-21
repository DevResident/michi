package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.navigation.domain.DetailDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DetailDestinationTest {

    @Test
    fun theSameIdUnderDifferentProvidersIsADifferentMedia() {
        val aniList = DetailDestination.Media(aniListRef(1), MediaType.ANIME)
        val other = DetailDestination.Media(EntityRef(ProviderId("other"), "1"), MediaType.ANIME)

        assertNotEquals(aniList, other)
    }

    @Test
    fun theTypeHintNeverTakesPartInEquality() {
        val withoutHint = DetailDestination.Media(aniListRef(1))
        val anime = DetailDestination.Media(aniListRef(1), MediaType.ANIME)
        val manga = DetailDestination.Media(aniListRef(1), MediaType.MANGA)

        assertEquals(withoutHint, anime)
        assertEquals(anime, manga)
        assertEquals(anime.hashCode(), manga.hashCode())
    }
}
