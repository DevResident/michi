package com.luum.michi.app.core

import com.luum.michi.app.core.mal.domain.isMalOnlyAniListRef
import com.luum.michi.app.core.mal.domain.isMalOnlyMediaId
import com.luum.michi.app.core.mal.domain.malIdOfMalOnly
import com.luum.michi.app.core.mal.domain.malMediaTypeToFormat
import com.luum.michi.app.core.mal.domain.malOnlyMediaId
import com.luum.michi.app.core.mal.domain.malPageUrl
import com.luum.michi.app.core.mal.domain.malWorkStatusToMediaStatus
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.domain.aniListRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalOnlyMediaTest {

    @Test
    fun syntheticIdsRoundTripAndNeverLookLikeAniListIds() {
        val id = malOnlyMediaId(38414)
        assertEquals(-38414, id)
        assertTrue(isMalOnlyMediaId(id))
        assertEquals(38414, malIdOfMalOnly(id))
        assertFalse(isMalOnlyMediaId(1))
        assertTrue(isMalOnlyMediaId(0), "0 is not a valid AniList id either")
    }

    @Test
    fun onlyAniListRefsWithASyntheticIdAreMalOnly() {
        assertTrue(aniListRef(-5).isMalOnlyAniListRef())
        assertFalse(aniListRef(5).isMalOnlyAniListRef())
        assertFalse(EntityRef(ProviderId.Mal, "-5").isMalOnlyAniListRef())
    }

    @Test
    fun pageUrlsPointAtMyAnimeList() {
        assertEquals("https://myanimelist.net/anime/38414", malPageUrl(MediaType.ANIME, 38414))
        assertEquals("https://myanimelist.net/manga/2", malPageUrl(MediaType.MANGA, 2))
    }

    @Test
    fun malVocabularyMapsToAniListVocabulary() {
        assertEquals("TV", malMediaTypeToFormat("tv"))
        assertEquals("SPECIAL", malMediaTypeToFormat("tv_special"))
        assertEquals("NOVEL", malMediaTypeToFormat("light_novel"))
        assertEquals("MANGA", malMediaTypeToFormat("manhwa"))
        assertNull(malMediaTypeToFormat("unknown"))
        assertEquals("RELEASING", malWorkStatusToMediaStatus("currently_airing"))
        assertEquals("CANCELLED", malWorkStatusToMediaStatus("discontinued"))
        assertNull(malWorkStatusToMediaStatus(null))
    }
}
