package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorTranslator
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun mirrorEntry(
    sourceProvider: ProviderId = ProviderId.AniList,
    type: MediaType = MediaType.ANIME,
    status: MediaListStatus = MediaListStatus.CURRENT,
    score: Float = 8.5f,
    scoreFormat: ScoreFormat = ScoreFormat.POINT_10_DECIMAL,
    repeat: Int = 1,
    priority: Int = 2,
    notes: String = "note",
    isPrivate: Boolean = false,
    isRewatching: Boolean = false,
    startedAt: MirrorDate? = MirrorDate(2024, 5, 6),
    completedAt: MirrorDate? = null,
) = MirrorEntry(
    sourceProvider = sourceProvider,
    aniListId = 10,
    malId = 20,
    type = type,
    status = status,
    progress = 3,
    progressVolumes = if (type == MediaType.MANGA) 2 else null,
    score = score,
    scoreFormat = scoreFormat,
    repeat = repeat,
    priority = priority,
    notes = notes,
    isPrivate = isPrivate,
    isRewatching = isRewatching,
    startedAt = startedAt,
    completedAt = completedAt,
)

class MirrorTranslatorTest {

    @Test
    fun aniListScoresNormalizeToTheOneHundredScale() {
        assertEquals(85f, MirrorTranslator.normalizeScore(85f, ScoreFormat.POINT_100))
        assertEquals(85f, MirrorTranslator.normalizeScore(8.5f, ScoreFormat.POINT_10_DECIMAL))
        assertEquals(80f, MirrorTranslator.normalizeScore(8f, ScoreFormat.POINT_10))
        assertEquals(80f, MirrorTranslator.normalizeScore(4f, ScoreFormat.POINT_5_STARS))
        assertEquals(100f, MirrorTranslator.normalizeScore(3f, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(0f, MirrorTranslator.normalizeScore(0f, ScoreFormat.POINT_100))
    }

    @Test
    fun malScoresAreIntegersAndZeroStaysUnrated() {
        assertEquals(9, MirrorTranslator.toMalScore(8.5f, ScoreFormat.POINT_10_DECIMAL))
        assertEquals(10, MirrorTranslator.toMalScore(100f, ScoreFormat.POINT_100))
        assertEquals(3, MirrorTranslator.toMalScore(1f, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(7, MirrorTranslator.toMalScore(2f, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(0, MirrorTranslator.toMalScore(0f, ScoreFormat.POINT_10))
        assertEquals(1, MirrorTranslator.toMalScore(1f, ScoreFormat.POINT_100), "a real score never becomes unrated")
    }

    @Test
    fun aniListTargetScoresUseItsScoreFormat() {
        assertEquals(85f, MirrorTranslator.toAniListScore(8.5f, ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_100))
        assertEquals(8.5f, MirrorTranslator.toAniListScore(85f, ScoreFormat.POINT_100, ScoreFormat.POINT_10_DECIMAL))
        assertEquals(9f, MirrorTranslator.toAniListScore(85f, ScoreFormat.POINT_100, ScoreFormat.POINT_10))
        assertEquals(4f, MirrorTranslator.toAniListScore(85f, ScoreFormat.POINT_100, ScoreFormat.POINT_5_STARS))
        assertEquals(3f, MirrorTranslator.toAniListScore(85f, ScoreFormat.POINT_100, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(2f, MirrorTranslator.toAniListScore(50f, ScoreFormat.POINT_100, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(1f, MirrorTranslator.toAniListScore(20f, ScoreFormat.POINT_100, ScoreFormat.POINT_3_SMILEYS))
        assertEquals(0f, MirrorTranslator.toAniListScore(0f, ScoreFormat.POINT_10, ScoreFormat.POINT_100))
    }

    @Test
    fun malEntryMapsDirectlyToAniListFields() {
        val values = MirrorTranslator.toMal(mirrorEntry())!!
        assertEquals("watching", values.status)
        assertEquals(9, values.score)
        assertEquals(3, values.progress)
        assertEquals(1, values.priority)
        assertEquals(1, values.repeat)
        assertEquals("note", values.comments)
        assertFalse(values.isRewatching)
        assertEquals(MirrorDate(2024, 5, 6), values.startDate)
        assertEquals(null, values.finishDate)
    }

    @Test
    fun repeatingBecomesWatchingPlusTheRewatchFlag() {
        val values = MirrorTranslator.toMal(
            mirrorEntry(status = MediaListStatus.REPEATING),
        )!!
        assertEquals("watching", values.status)
        assertTrue(values.isRewatching)
    }

    @Test
    fun mangaStatusesUseReadingAndVolumes() {
        val values = MirrorTranslator.toMal(
            mirrorEntry(type = MediaType.MANGA, status = MediaListStatus.REPEATING),
        )!!
        assertEquals("reading", values.status)
        assertEquals(2, values.progressVolumes)
        assertTrue(values.isRewatching)
    }

    @Test
    fun malRewatchBecomesRepeatingInAniList() {
        val values = MirrorTranslator.toAniList(
            mirrorEntry(
                sourceProvider = ProviderId.Mal,
                status = MediaListStatus.CURRENT,
                score = 8f,
                scoreFormat = ScoreFormat.POINT_10,
                isRewatching = true,
            ),
            targetFormat = ScoreFormat.POINT_100,
        )!!
        assertEquals(MediaListStatus.REPEATING, values.status)
        assertEquals(80f, values.score)
        assertEquals(3, values.priority, "MAL high priority maps to AniList high")
    }

    @Test
    fun malPriorityRoundTripsThroughTheFixedTable() {
        assertEquals(0, MirrorTranslator.priorityToMal(0))
        assertEquals(1, MirrorTranslator.priorityToMal(1))
        assertEquals(1, MirrorTranslator.priorityToMal(2))
        assertEquals(2, MirrorTranslator.priorityToMal(3))
        assertEquals(2, MirrorTranslator.priorityToMal(5))
        assertEquals(0, MirrorTranslator.priorityToAniList(0))
        assertEquals(1, MirrorTranslator.priorityToAniList(1))
        assertEquals(3, MirrorTranslator.priorityToAniList(2))
    }

    @Test
    fun partialDatesKeepTheirDetail() {
        val yearOnly = MirrorDate(2020)
        val yearMonth = MirrorDate(2020, 7)
        assertEquals(yearOnly, MirrorTranslator.toMal(mirrorEntry(startedAt = yearOnly))!!.startDate)
        assertEquals(
            yearMonth,
            MirrorTranslator.toAniList(
                mirrorEntry(startedAt = yearMonth),
                targetFormat = ScoreFormat.POINT_10,
            )!!.startedAt,
        )
    }

    @Test
    fun advancedPrivateAndHiddenNeverEnterTheTargetValues() {
        // The target value types deliberately have no private/hidden/advanced
        // fields; the wrapper skips private sources. This guards the contract.
        val values = MirrorTranslator.toAniList(
            mirrorEntry(isPrivate = true),
            targetFormat = ScoreFormat.POINT_10,
        )!!
        assertEquals(MediaListStatus.CURRENT, values.status)
        assertEquals("note", values.notes)
    }
}
