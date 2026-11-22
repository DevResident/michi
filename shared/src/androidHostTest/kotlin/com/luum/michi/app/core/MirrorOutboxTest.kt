package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.AllMirrorFields
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MirrorOutbox
import com.luum.michi.app.core.medialist.repository.MirrorBatchItem
import com.luum.michi.app.core.medialist.repository.MirrorOutboxFileName
import com.luum.michi.app.core.medialist.repository.MirrorOutboxKey
import com.luum.michi.app.mediaList.InMemoryFileStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun outboxEntry(
    progress: Int = 1,
    status: MediaListStatus = MediaListStatus.CURRENT,
    notes: String = "old note",
    score: Float = 8.5f,
    startedAt: MirrorDate? = MirrorDate(2024, 1, 2),
) = MirrorEntry(
    sourceProvider = ProviderId.AniList,
    aniListId = 10,
    malId = 20,
    type = MediaType.ANIME,
    status = status,
    progress = progress,
    progressVolumes = null,
    score = score,
    scoreFormat = ScoreFormat.POINT_10_DECIMAL,
    repeat = 1,
    priority = 2,
    notes = notes,
    isPrivate = false,
    isRewatching = false,
    startedAt = startedAt,
    completedAt = null,
)

class MirrorOutboxTest {

    @Test
    fun pendingOperationsSurviveARestart() = runBlocking {
        val files = InMemoryFileStore()
        val outbox = MirrorOutbox(files)
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        assertTrue(files.contains(MirrorOutboxFileName))

        val restarted = MirrorOutbox(files)
        restarted.load()

        assertEquals(1, restarted.state.value.pending)
        val due = assertNotNull(restarted.nextDue(now = 0L))
        assertEquals(10, due.entry.aniListId)
        assertEquals(AllMirrorFields, due.fields)
    }

    @Test
    fun aProgressSaveMergesItsFieldsAndKeepsTheOldNote() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(progress = 1, notes = "old note"), AllMirrorFields)
        outbox.enqueue(
            outboxEntry(progress = 4, status = MediaListStatus.COMPLETED, notes = "not selected"),
            setOf(MirrorField.STATUS, MirrorField.PROGRESS),
        )

        val due = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(4, due.entry.progress)
        assertEquals(MediaListStatus.COMPLETED, due.entry.status)
        assertEquals("old note", due.entry.notes, "a +1 never overwrites notes it did not select")
        assertEquals(AllMirrorFields, due.fields)
    }

    @Test
    fun coalescingUsesTheLatestValueOfEachSelectedField() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(progress = 1, score = 8.5f), AllMirrorFields)
        outbox.enqueue(
            outboxEntry(progress = 2, score = 9f),
            setOf(MirrorField.PROGRESS, MirrorField.SCORE),
        )

        val due = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(2, due.entry.progress)
        assertEquals(9f, due.entry.score)
        assertEquals("old note", due.entry.notes)
    }

    @Test
    fun statusThenNoteSavesCoalesceIntoOneOperationWithBothFields() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(
            outboxEntry(status = MediaListStatus.CURRENT, notes = "old note"),
            setOf(MirrorField.STATUS),
        )
        outbox.enqueue(
            outboxEntry(status = MediaListStatus.CURRENT, notes = "new note"),
            setOf(MirrorField.NOTES),
        )

        val due = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(setOf(MirrorField.STATUS, MirrorField.NOTES), due.fields)
        assertEquals(MediaListStatus.CURRENT, due.entry.status)
        assertEquals("new note", due.entry.notes)
        assertEquals(1, outbox.state.value.pending)
    }

    @Test
    fun aStaleSuccessNeverRemovesTheNewerOperation() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(progress = 1), AllMirrorFields)
        val old = assertNotNull(outbox.nextDue(now = 0L))
        outbox.enqueue(
            outboxEntry(progress = 2),
            setOf(MirrorField.PROGRESS),
        )

        outbox.markSuccess(old)

        val current = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(2, current.entry.progress)
    }

    @Test
    fun transientFailuresBackOffAndThenParkAfterTenAttempts() = runBlocking {
        var now = 1_000L
        val outbox = MirrorOutbox(InMemoryFileStore(), nowMillis = { now })
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)

        repeat(10) {
            val due = assertNotNull(outbox.nextDue(now = now))
            outbox.markTransient(due, "offline")
            now += 60L * 60L * 1000L
        }

        assertEquals(1, outbox.state.value.withErrors)
        assertNull(outbox.nextDue(now = now), "a parked operation is not retried automatically")

        outbox.retryFailed()

        val retried = assertNotNull(outbox.nextDue(now = now))
        assertEquals(0, retried.attempts)
        assertNull(retried.lastError)
        assertEquals(0, outbox.state.value.withErrors)
    }

    @Test
    fun aNewSaveReArmsAFailedOperation() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        val first = assertNotNull(outbox.nextDue(now = 0L))
        outbox.markTransient(first, "offline")
        assertTrue(outbox.state.value.withErrors > 0)

        outbox.enqueue(outboxEntry(progress = 3), setOf(MirrorField.PROGRESS))

        val due = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(0, due.attempts)
        assertNull(due.lastError)
        assertEquals(3, due.entry.progress)
    }

    @Test
    fun aFailureOlderThanTwentyFourHoursStopsBeingRetried() = runBlocking {
        var now = 0L
        val outbox = MirrorOutbox(InMemoryFileStore(), nowMillis = { now })
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        val first = assertNotNull(outbox.nextDue(now = now))
        outbox.markTransient(first, "offline")

        now = 24L * 60L * 60L * 1000L + 1L
        val second = assertNotNull(outbox.nextDue(now = now))
        outbox.markTransient(second, "offline")

        assertEquals(1, outbox.state.value.withErrors)
        assertNull(outbox.nextDue(now = now), "the 24 h window parks the operation")
    }

    @Test
    fun permanentFailuresAreDroppedAndCountedAcrossRestart() = runBlocking {
        val files = InMemoryFileStore()
        val outbox = MirrorOutbox(files)
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        val due = assertNotNull(outbox.nextDue(now = 0L))

        outbox.markPermanent(due, "not on MAL")

        assertEquals(0, outbox.state.value.pending)
        assertEquals(1, outbox.state.value.dropped)
        assertEquals(1, outbox.state.value.dropped)

        val restarted = MirrorOutbox(files)
        restarted.load()
        assertEquals(1, restarted.state.value.dropped)
        assertNull(restarted.nextDue(now = 0L))
    }

    @Test
    fun aBatchTracksEachFieldSetAndItsProgress() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        val first = outboxEntry(progress = 1)
        val second = outboxEntry(progress = 2).copy(aniListId = 11)
        outbox.beginBatch(
            listOf(
                MirrorBatchItem(first, setOf(MirrorField.PROGRESS)),
                MirrorBatchItem(second, AllMirrorFields),
            ),
        )

        assertEquals(2, outbox.state.value.batch?.total)
        assertEquals(0, outbox.state.value.batch?.done)
        assertTrue(outbox.state.value.batch?.active == true)

        val firstDue = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(setOf(MirrorField.PROGRESS), firstDue.fields)
        outbox.markSuccess(firstDue)
        assertEquals(1, outbox.state.value.batch?.done)

        val secondDue = assertNotNull(outbox.nextDue(now = 0L))
        outbox.markPermanent(secondDue, "not on target")
        assertEquals(1, outbox.state.value.batch?.errors)
        assertFalse(outbox.state.value.batch?.active == true)
    }

    @Test
    fun theSameWorkCanTravelInBothDirectionsWithoutCoalescing() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(), setOf(MirrorField.PROGRESS))
        outbox.enqueue(
            outboxEntry(progress = 2).copy(sourceProvider = ProviderId.Mal),
            setOf(MirrorField.NOTES),
        )

        assertEquals(2, outbox.state.value.pending)
        val first = assertNotNull(outbox.nextDue(now = 0L))
        outbox.markSuccess(first)
        val second = assertNotNull(outbox.nextDue(now = 0L))
        assertEquals(ProviderId.AniList, second.targetProvider)
        assertEquals(setOf(MirrorField.NOTES), second.fields)
        assertEquals(MirrorOutboxKey(ProviderId.Mal, 10), MirrorOutboxKey(first.targetProvider, first.entry.aniListId))
    }

    @Test
    fun discardingOneTargetKeepsTheOtherQueue() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        outbox.enqueue(
            outboxEntry(progress = 2).copy(sourceProvider = ProviderId.Mal),
            AllMirrorFields,
        )

        outbox.discardTarget(ProviderId.Mal)

        assertEquals(1, outbox.state.value.pending)
        assertEquals(ProviderId.AniList, outbox.nextDue(now = 0L)?.targetProvider)
    }

    @Test
    fun discardingABatchTargetAdjustsItsProgress() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        val malTarget = outboxEntry()
        val aniListTarget = outboxEntry(progress = 2).copy(sourceProvider = ProviderId.Mal)
        outbox.beginBatch(
            listOf(
                MirrorBatchItem(malTarget, AllMirrorFields),
                MirrorBatchItem(aniListTarget, AllMirrorFields),
            ),
        )

        outbox.discardTarget(ProviderId.Mal)

        assertEquals(1, outbox.state.value.batch?.total)
        assertEquals(1, outbox.state.value.pending)
        assertEquals(ProviderId.AniList, outbox.nextDue(now = 0L)?.targetProvider)
    }

    @Test
    fun clearRemovesTheFileAndAllState() = runBlocking {
        val files = InMemoryFileStore()
        val outbox = MirrorOutbox(files)
        outbox.load()
        outbox.enqueue(outboxEntry(), AllMirrorFields)
        assertTrue(files.contains(MirrorOutboxFileName))

        outbox.clear()

        assertFalse(files.contains(MirrorOutboxFileName))
        assertEquals(0, outbox.state.value.pending)
        assertNull(outbox.nextDue(now = 0L))
    }
}
