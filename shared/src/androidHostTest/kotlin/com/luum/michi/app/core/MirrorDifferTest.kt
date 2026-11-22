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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun media(type: MediaType = MediaType.ANIME, malId: Int? = 20) = MirrorMediaSummary(
    aniListId = 10,
    malId = malId,
    type = type,
    title = "Test",
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

private fun snapshot(provider: ProviderId, vararg entries: MirrorEntry) = MirrorSnapshot(
    provider = provider,
    entries = entries.map { MirrorSnapshotEntry(media(it.type), it) },
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
}
