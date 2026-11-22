package com.luum.michi.app.mediaList

import com.luum.michi.app.core.mal.domain.malStatusToMediaListStatus
import com.luum.michi.app.core.mal.repository.parseMalFuzzyDate
import com.luum.michi.app.core.mal.repository.parseMalUpdatedAt
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.mediaList.repository.toComparableInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MalListMappingTest {

    @Test
    fun statusesCollapseOntoAniListSections() {
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus("watching"))
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus("reading"))
        assertEquals(MediaListStatus.COMPLETED, malStatusToMediaListStatus("completed"))
        assertEquals(MediaListStatus.PAUSED, malStatusToMediaListStatus("on_hold"))
        assertEquals(MediaListStatus.DROPPED, malStatusToMediaListStatus("dropped"))
        assertEquals(MediaListStatus.PLANNING, malStatusToMediaListStatus("plan_to_watch"))
        assertEquals(MediaListStatus.PLANNING, malStatusToMediaListStatus("plan_to_read"))
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus(null))
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus("unknown"))
    }

    @Test
    fun rewatchFlagsDoNotCreateAnotherStatus() {
        // The flag is not an input to the mapping: watching/reading stays CURRENT.
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus("watching"))
        assertEquals(MediaListStatus.CURRENT, malStatusToMediaListStatus("reading"))
    }

    @Test
    fun partialDatesBecomeTheSameComparableIntAsAniList() {
        assertEquals(20200000, parseMalFuzzyDate("2020").toComparableInt())
        assertEquals(20201000, parseMalFuzzyDate("2020-10").toComparableInt())
        assertEquals(20201005, parseMalFuzzyDate("2020-10-05").toComparableInt())
        assertEquals(20200105, parseMalFuzzyDate("2020-1-5").toComparableInt())
        assertNull(parseMalFuzzyDate(null))
        assertNull(parseMalFuzzyDate(""))
        assertEquals(0, parseMalFuzzyDate(null).toComparableInt())
    }

    @Test
    fun updatedAtBecomesEpochSeconds() {
        assertEquals(1_577_836_800L, parseMalUpdatedAt("2020-01-01T00:00:00+00:00"))
        assertEquals(1_577_836_800L, parseMalUpdatedAt("2020-01-1T00:00:00+00:00"))
        assertNull(parseMalUpdatedAt("not-a-date"))
        assertNull(parseMalUpdatedAt(null))
    }
}
