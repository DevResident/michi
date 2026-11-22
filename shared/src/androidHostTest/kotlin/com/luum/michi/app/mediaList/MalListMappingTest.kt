package com.luum.michi.app.mediaList

import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.mediaList.repository.mal.malListStatusToMediaListStatus
import com.luum.michi.app.mediaList.repository.mal.parseMalFuzzyDate
import com.luum.michi.app.mediaList.repository.mal.parseMalUpdatedAt
import com.luum.michi.app.mediaList.repository.toComparableInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MalListMappingTest {

    @Test
    fun statusesCollapseOntoAniListSections() {
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus("watching"))
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus("reading"))
        assertEquals(MediaListStatus.COMPLETED, malListStatusToMediaListStatus("completed"))
        assertEquals(MediaListStatus.PAUSED, malListStatusToMediaListStatus("on_hold"))
        assertEquals(MediaListStatus.DROPPED, malListStatusToMediaListStatus("dropped"))
        assertEquals(MediaListStatus.PLANNING, malListStatusToMediaListStatus("plan_to_watch"))
        assertEquals(MediaListStatus.PLANNING, malListStatusToMediaListStatus("plan_to_read"))
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus(null))
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus("unknown"))
    }

    @Test
    fun rewatchFlagsDoNotCreateAnotherStatus() {
        // The flag is not an input to the mapping: watching/reading stays CURRENT.
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus("watching"))
        assertEquals(MediaListStatus.CURRENT, malListStatusToMediaListStatus("reading"))
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
