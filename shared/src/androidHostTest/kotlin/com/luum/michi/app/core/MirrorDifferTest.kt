package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.AllMirrorFields
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorDiffer
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorSnapshot
import com.luum.michi.app.core.medialist.domain.MirrorSnapshotEntry
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun media(
    type: MediaType = MediaType.ANIME,
    malId: Int? = 20,
    totalEpisodes: Int? = null,
    totalChapters: Int? = null,
    totalVolumes: Int? = null,
) = MirrorMediaSummary(
    aniListId = 10,
    malId = malId,
    type = type,
    title = "Test",
    totalEpisodes = totalEpisodes,
    totalChapters = totalChapters,
    totalVolumes = totalVolumes,
)

private fun entry(
    provider: ProviderId = ProviderId.AniList,
    status: MediaListStatus = MediaListStatus.CURRENT,
    progress: Int = 3,
    score: Float = 85f,
    scoreFormat: ScoreFormat = ScoreFormat.POINT_100,
    priority: Int = 3,
    notes: String = "old note",
    repeat: Int = 1,
    startedAt: MirrorDate? = MirrorDate(2024, 5, 6),
    completedAt: MirrorDate? = null,
    isPrivate: Boolean = false,
    isRewatching: Boolean = false,
    updatedAt: Long? = 100L,
    type: MediaType = MediaType.ANIME,
) = MirrorEntry(
    sourceProvider = provider,
    aniListId = 10,
    malId = 20,
    type = type,
    status = status,
    progress = progress,
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
    updatedAt = updatedAt,
)

private fun snapshot(
    provider: ProviderId,
    vararg entries: MirrorEntry,
    totalEpisodes: Int? = null,
    totalChapters: Int? = null,
    totalVolumes: Int? = null,
) = MirrorSnapshot(
    provider = provider,
    entries = entries.map {
        MirrorSnapshotEntry(
            media(
                type = it.type,
                malId = it.malId,
                totalEpisodes = totalEpisodes,
                totalChapters = totalChapters,
                totalVolumes = totalVolumes,
            ),
            it,
        )
    },
)

private fun malEntry(
    status: MediaListStatus = MediaListStatus.CURRENT,
    progress: Int = 3,
    score: Float = 9f,
    priority: Int = 2,
    notes: String = "old note",
    repeat: Int = 1,
    startedAt: MirrorDate? = MirrorDate(2024, 5, 6),
    completedAt: MirrorDate? = null,
    isRewatching: Boolean = false,
    updatedAt: Long? = 100L,
) = entry(
    provider = ProviderId.Mal,
    status = status,
    progress = progress,
    score = score,
    scoreFormat = ScoreFormat.POINT_10,
    priority = priority,
    notes = notes,
    repeat = repeat,
    startedAt = startedAt,
    completedAt = completedAt,
    isRewatching = isRewatching,
    updatedAt = updatedAt,
)

class MirrorDifferTest {

    private fun diff(primary: MirrorSnapshot, linked: MirrorSnapshot, target: ProviderId = ProviderId.Mal) =
        MirrorDiffer.diff(
            primary = primary,
            linked = linked,
            targetProvider = target,
            targetScoreFormat = ScoreFormat.POINT_10,
        )

    @Test
    fun anAdditionCarriesTheWholeEntry() {
        val result = diff(
            snapshot(ProviderId.AniList, entry()),
            snapshot(ProviderId.Mal),
        )

        val row = result.additions.single()
        assertEquals(AllMirrorFields, row.changedFields)
        assertNull(row.before)
        assertEquals(MediaListStatus.CURRENT, row.after.status)
        assertEquals(3, row.after.progress)
        assertEquals(9f, row.after.score)
        assertEquals(2, row.after.priority)
        assertEquals("old note", row.after.notes)
        assertEquals(MirrorDate(2024, 5, 6), row.after.startedAt)
    }

    @Test
    fun equivalentScoresAndPrioritiesDoNotCreateAnUpdate() {
        val result = diff(
            snapshot(ProviderId.AniList, entry()),
            snapshot(ProviderId.Mal, malEntry()),
        )

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun notesDifferingDoesCreateAnUpdate() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(notes = "new note")),
            snapshot(ProviderId.Mal, malEntry(notes = "old note")),
        )

        val row = result.updates.single()
        assertEquals(setOf(MirrorField.NOTES), row.changedFields)
        assertEquals("old note", row.before?.notes)
        assertEquals("new note", row.after.notes)
    }

    @Test
    fun emptyPrimaryNotesNeverEraseFilledLinkedNotes() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(progress = 4, notes = "")),
            snapshot(ProviderId.Mal, malEntry(progress = 3, notes = "keep me")),
        )

        val row = result.updates.single()
        assertEquals(setOf(MirrorField.PROGRESS), row.changedFields)
        assertEquals("keep me", row.after.notes)
        assertTrue(MirrorField.NOTES !in row.changedFields)
    }

    @Test
    fun emptyPrimaryScorePriorityRepeatAndDatesNeverEraseLinkedValues() {
        val result = diff(
            snapshot(
                ProviderId.AniList,
                entry(
                    progress = 4,
                    score = 0f,
                    priority = 0,
                    notes = " ",
                    repeat = 0,
                    startedAt = null,
                    completedAt = null,
                ),
            ),
            snapshot(
                ProviderId.Mal,
                malEntry(
                    progress = 3,
                    score = 9f,
                    priority = 2,
                    notes = "keep me",
                    repeat = 3,
                    startedAt = MirrorDate(2020, 1, 2),
                    completedAt = MirrorDate(2021, 3, 4),
                ),
            ),
        )

        val row = result.updates.single()
        assertEquals(setOf(MirrorField.PROGRESS), row.changedFields)
        assertEquals(9f, row.after.score)
        assertEquals(2, row.after.priority)
        assertEquals("keep me", row.after.notes)
        assertEquals(3, row.after.repeat)
        assertEquals(MirrorDate(2020, 1, 2), row.after.startedAt)
        assertEquals(MirrorDate(2021, 3, 4), row.after.completedAt)
    }

    @Test
    fun newerInLinkedIsMarked() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(progress = 4, updatedAt = 100L)),
            snapshot(ProviderId.Mal, malEntry(progress = 3, updatedAt = 200L)),
        )

        assertTrue(result.updates.single().newerInLinked)
    }

    @Test
    fun privatePrimaryIsCountedAndSuppressesLinkedOnly() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(isPrivate = true)),
            snapshot(ProviderId.Mal, malEntry()),
        )

        assertEquals(1, result.privateOmitted)
        assertTrue(result.additions.isEmpty())
        assertTrue(result.updates.isEmpty())
        assertTrue(result.linkedOnly.isEmpty())
    }

    @Test
    fun malOnlyPrimaryNeverTargetsAniList() {
        val malOnly = entry(
            provider = ProviderId.Mal,
            scoreFormat = ScoreFormat.POINT_10,
            score = 9f,
        ).copy(aniListId = -20)
        val result = MirrorDiffer.diff(
            primary = snapshot(ProviderId.Mal, malOnly),
            linked = snapshot(ProviderId.AniList),
            targetProvider = ProviderId.AniList,
            targetScoreFormat = ScoreFormat.POINT_100,
        )

        assertEquals(1, result.noEquivalent)
        assertTrue(result.additions.isEmpty())
    }

    @Test
    fun partialDatesCompareByTheirPresentParts() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(startedAt = MirrorDate(2024, 5))),
            snapshot(ProviderId.Mal, malEntry(startedAt = MirrorDate(2024, 5))),
        )

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun repeatingAndMalRewatchAreEquivalent() {
        val result = diff(
            snapshot(
                ProviderId.AniList,
                entry(status = MediaListStatus.REPEATING, isRewatching = true),
            ),
            snapshot(
                ProviderId.Mal,
                malEntry(status = MediaListStatus.CURRENT, isRewatching = true),
            ),
        )

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun theTargetsOwnTotalCapsProgress() {
        val result = diff(
            snapshot(
                ProviderId.AniList,
                entry(status = MediaListStatus.COMPLETED, progress = 13),
                totalEpisodes = 13,
            ),
            snapshot(
                ProviderId.Mal,
                malEntry(status = MediaListStatus.COMPLETED, progress = 12),
                totalEpisodes = 12,
            ),
        )

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun anUnknownOrZeroTotalNeverCaps() {
        val unknown = diff(
            snapshot(ProviderId.AniList, entry(progress = 13)),
            snapshot(ProviderId.Mal, malEntry(progress = 12)),
        )
        val zero = diff(
            snapshot(ProviderId.AniList, entry(progress = 13)),
            snapshot(ProviderId.Mal, malEntry(progress = 12), totalEpisodes = 0),
        )

        assertEquals(setOf(MirrorField.PROGRESS), unknown.updates.single().changedFields)
        assertEquals(setOf(MirrorField.PROGRESS), zero.updates.single().changedFields)
    }

    @Test
    fun aRealIncreaseStillUpdates() {
        val result = diff(
            snapshot(ProviderId.AniList, entry(status = MediaListStatus.COMPLETED, progress = 24)),
            snapshot(
                ProviderId.Mal,
                malEntry(status = MediaListStatus.CURRENT, progress = 12),
                totalEpisodes = 24,
            ),
        )

        val row = result.updates.single()
        assertTrue(MirrorField.PROGRESS in row.changedFields)
        assertEquals(24, row.after.progress)
        assertFalse(row.regression)
    }

    @Test
    fun loweringProgressIsARegression() {
        val result = MirrorDiffer.diff(
            primary = snapshot(
                ProviderId.Mal,
                malEntry(status = MediaListStatus.CURRENT, progress = 12),
                totalEpisodes = 24,
            ),
            linked = snapshot(
                ProviderId.AniList,
                entry(
                    status = MediaListStatus.COMPLETED,
                    progress = 24,
                    score = 9f,
                    scoreFormat = ScoreFormat.POINT_100,
                ),
                totalEpisodes = 24,
            ),
            targetProvider = ProviderId.AniList,
            targetScoreFormat = ScoreFormat.POINT_100,
        )

        val row = result.updates.single()
        assertTrue(row.regression)
        assertTrue(MirrorField.PROGRESS in row.changedFields)
    }

    @Test
    fun leavingCompletedIsARegression() {
        val result = MirrorDiffer.diff(
            primary = snapshot(
                ProviderId.Mal,
                malEntry(status = MediaListStatus.CURRENT, progress = 12),
                totalEpisodes = 24,
            ),
            linked = snapshot(
                ProviderId.AniList,
                entry(status = MediaListStatus.COMPLETED, progress = 12),
                totalEpisodes = 24,
            ),
            targetProvider = ProviderId.AniList,
            targetScoreFormat = ScoreFormat.POINT_100,
        )

        assertTrue(result.updates.single().regression)
    }

    @Test
    fun catchUpSkipsNewerAndRegressions() {
        val primary = snapshot(
            ProviderId.Mal,
            malEntry(status = MediaListStatus.CURRENT, progress = 12, notes = "a"),
            malEntry(status = MediaListStatus.CURRENT, progress = 12, notes = "b").copy(aniListId = 11, malId = 21),
            malEntry(status = MediaListStatus.CURRENT, progress = 12, notes = "c").copy(aniListId = 12, malId = 22),
        )
        val linked = snapshot(
            ProviderId.AniList,
            entry(status = MediaListStatus.CURRENT, progress = 24, notes = "old", updatedAt = 100L),
            entry(status = MediaListStatus.CURRENT, progress = 12, notes = "old", updatedAt = 50L)
                .copy(aniListId = 11, malId = 21),
            entry(status = MediaListStatus.CURRENT, progress = 12, notes = "old", updatedAt = 200L)
                .copy(aniListId = 12, malId = 22),
        )
        val result = MirrorDiffer.diff(
            primary = primary,
            linked = linked,
            targetProvider = ProviderId.AniList,
            targetScoreFormat = ScoreFormat.POINT_100,
        )

        // 12 is a regression; 11 is newer in the primary (not newerInLinked);
        // 12 (third) is newerInLinked because the linked entry is newer.
        assertTrue(result.updates.size >= 2)
        assertTrue(result.catchUpRows.size < result.updates.size)
    }

    @Test
    fun roundTripForEachFieldIsStable() {
        val primaries = listOf(
            entry(),
            entry(status = MediaListStatus.COMPLETED, progress = 12, completedAt = MirrorDate(2024, 6, 1)),
            entry(status = MediaListStatus.PAUSED, score = 0f, priority = 0, repeat = 0),
            entry(notes = "", startedAt = MirrorDate(2020), completedAt = null),
            entry(status = MediaListStatus.REPEATING, isRewatching = true, repeat = 2),
            entry(progress = 4, score = 85f, priority = 5, notes = "x", repeat = 3),
        )
        primaries.forEach { primary ->
            val linked = linkedMal(primary)
            val result = diff(
                snapshot(ProviderId.AniList, primary, totalEpisodes = 100),
                MirrorSnapshot(
                    ProviderId.Mal,
                    listOf(MirrorSnapshotEntry(media(totalEpisodes = 100), linked)),
                ),
            )
            assertTrue(result.updates.isEmpty(), "round trip diff for $primary")
        }
    }

    @Test
    fun roundTripToAniListIsStable() {
        val primary = malEntry(
            status = MediaListStatus.REPEATING,
            progress = 12,
            score = 9f,
            priority = 2,
            notes = "note",
            repeat = 2,
            isRewatching = true,
        )
        val values = com.luum.michi.app.core.medialist.domain.MirrorTranslator
            .toAniList(primary, ScoreFormat.POINT_10_DECIMAL)!!
        val linked = MirrorEntry(
            sourceProvider = ProviderId.AniList,
            aniListId = 10,
            malId = 20,
            type = MediaType.ANIME,
            status = values.status,
            progress = values.progress,
            progressVolumes = values.progressVolumes,
            score = values.score,
            scoreFormat = ScoreFormat.POINT_10_DECIMAL,
            repeat = values.repeat,
            priority = values.priority,
            notes = values.notes,
            isPrivate = false,
            isRewatching = values.status == MediaListStatus.REPEATING,
            startedAt = values.startedAt,
            completedAt = values.completedAt,
            updatedAt = 100L,
        )
        val result = MirrorDiffer.diff(
            primary = snapshot(ProviderId.Mal, primary, totalEpisodes = 24),
            linked = MirrorSnapshot(
                ProviderId.AniList,
                listOf(MirrorSnapshotEntry(media(totalEpisodes = 24), linked)),
            ),
            targetProvider = ProviderId.AniList,
            targetScoreFormat = ScoreFormat.POINT_10_DECIMAL,
        )

        assertTrue(result.updates.isEmpty())
    }

    private fun linkedMal(primary: MirrorEntry): MirrorEntry {
        val values = com.luum.michi.app.core.medialist.domain.MirrorTranslator.toMal(primary)!!
        return MirrorEntry(
            sourceProvider = ProviderId.Mal,
            aniListId = 10,
            malId = 20,
            type = primary.type,
            status = com.luum.michi.app.core.mal.domain
                .malStatusToMediaListStatus(values.status)
                .let { if (values.isRewatching) MediaListStatus.REPEATING else it },
            progress = values.progress,
            progressVolumes = values.progressVolumes,
            score = values.score.toFloat(),
            scoreFormat = ScoreFormat.POINT_10,
            repeat = values.repeat,
            priority = values.priority,
            notes = values.comments,
            isPrivate = false,
            isRewatching = values.isRewatching,
            startedAt = values.startDate,
            completedAt = values.finishDate,
            updatedAt = primary.updatedAt,
        )
    }
}
