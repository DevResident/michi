package com.luum.michi.app.mediaList

import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.AllMirrorFields
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MirrorBatchItem
import com.luum.michi.app.core.medialist.repository.MirrorOutbox
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.mediaList.repository.mirror.MirrorController
import com.luum.michi.app.mediaList.repository.mirror.MirrorWriter
import com.luum.michi.app.testLinkedAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun token() = AuthToken(
    accessToken = "linked-token",
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

private fun controllerEntry() = MirrorEntry(
    sourceProvider = ProviderId.AniList,
    aniListId = 10,
    malId = 20,
    type = MediaType.ANIME,
    status = MediaListStatus.CURRENT,
    progress = 1,
    progressVolumes = null,
    score = 8.5f,
    scoreFormat = ScoreFormat.POINT_10_DECIMAL,
    repeat = 1,
    priority = 2,
    notes = "note",
    isPrivate = false,
    isRewatching = false,
    startedAt = MirrorDate(2024, 1, 2),
    completedAt = null,
)

private class RecordingWriter(
    override val provider: ProviderId = ProviderId.Mal,
    private val results: ArrayDeque<NetworkResult<Unit>> = ArrayDeque(),
) : MirrorWriter {
    val calls = mutableListOf<Pair<Int, Set<MirrorField>>>()
    var defaultResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    override suspend fun write(
        entry: MirrorEntry,
        fields: Set<MirrorField>,
    ): NetworkResult<Unit> {
        calls += entry.aniListId to fields
        return results.removeFirstOrNull() ?: defaultResult
    }
}

private fun linkedManager(autoCopy: Boolean): LinkedAccountManager {
    val manager = testLinkedAccountManager(
        InMemoryLinkedAccountStore(
            account = LinkedAccount(
                provider = ProviderId.Mal,
                viewer = LinkedViewer(
                    id = 42,
                    name = "mal",
                    scoreFormat = ScoreFormat.POINT_10,
                ),
            ),
            autoCopy = autoCopy,
        ),
    )
    manager.bootstrap()
    return manager
}

private fun controller(
    outbox: MirrorOutbox,
    linked: LinkedAccountManager,
    writer: MirrorWriter,
    authenticated: Boolean = true,
    writers: Map<ProviderId, MirrorWriter> = mapOf(writer.provider to writer),
) = MirrorController(
    outbox = outbox,
    linkedAccounts = linked,
    writers = writers,
    followedProvider = { if (authenticated) ProviderId.AniList else null },
    sourceScoreFormat = { ScoreFormat.POINT_10_DECIMAL },
    isConnected = { authenticated },
    scope = CoroutineScope(Dispatchers.Unconfined),
    nowMillis = { 0L },
)

class MirrorControllerTest {

    @Test
    fun withoutAnAuthenticatedSessionNothingIsWrittenAndTheOutboxIsCleared() = runBlocking {
        val files = InMemoryFileStore()
        val outbox = MirrorOutbox(files)
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val linked = linkedManager(autoCopy = true)
        val writer = RecordingWriter()
        val controller = controller(outbox, linked, writer, authenticated = false)

        controller.drainDue(now = 0L)

        assertEquals(0, outbox.state.value.pending)
        assertNull(outbox.nextDue(now = 0L))
        assertTrue(writer.calls.isEmpty())
        assertTrue(files.read("mirror_outbox_v1.json").isNullOrBlank())
    }

    @Test
    fun startWithoutASessionClearsThePersistedOutbox() = runBlocking {
        val files = InMemoryFileStore()
        val outbox = MirrorOutbox(files)
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val linked = linkedManager(autoCopy = true)
        val writer = RecordingWriter()

        controller(outbox, linked, writer, authenticated = false).start()

        assertEquals(0, outbox.state.value.pending)
        assertTrue(writer.calls.isEmpty())
    }

    @Test
    fun autoCopyOffKeepsPendingOperationsPaused() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val linked = linkedManager(autoCopy = false)
        val writer = RecordingWriter()

        controller(outbox, linked, writer).drainDue(now = 0L)

        assertTrue(writer.calls.isEmpty())
        assertEquals(1, outbox.state.value.pending)
    }

    @Test
    fun aDueOperationIsWrittenAndRemoved() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val linked = linkedManager(autoCopy = true)
        val writer = RecordingWriter()

        controller(outbox, linked, writer).drainDue(now = 0L)

        assertEquals(1, writer.calls.size)
        assertEquals(0, outbox.state.value.pending)
    }

    @Test
    fun unauthorizedDropsOnlyThatTargetsOperations() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        outbox.enqueue(
            controllerEntry().copy(sourceProvider = ProviderId.Mal, aniListId = 11),
            AllMirrorFields,
        )
        val linked = linkedManager(autoCopy = true)
        val writer = RecordingWriter(
            results = ArrayDeque(listOf(NetworkResult.Failure(NetworkError.Unauthorized(null)))),
        )
        val aniListWriter = RecordingWriter(provider = ProviderId.AniList)

        controller(
            outbox = outbox,
            linked = linked,
            writer = writer,
            writers = mapOf(ProviderId.Mal to writer, ProviderId.AniList to aniListWriter),
        ).drainDue(now = 0L)

        assertEquals(1, aniListWriter.calls.size)
        assertEquals(0, outbox.state.value.pending)
    }

    @Test
    fun permanentAndTransientFailuresAreClassified() = runBlocking {
        val permanent = MirrorOutbox(InMemoryFileStore())
        permanent.load()
        permanent.enqueue(controllerEntry(), AllMirrorFields)
        controller(
            permanent,
            linkedManager(autoCopy = true),
            RecordingWriter(
                results = ArrayDeque(
                    listOf(NetworkResult.Failure(NetworkError.EntryNotOnProvider)),
                ),
            ),
        ).drainDue(now = 0L)
        assertEquals(0, permanent.state.value.pending)
        assertEquals(1, permanent.state.value.dropped)

        val transient = MirrorOutbox(InMemoryFileStore())
        transient.load()
        transient.enqueue(controllerEntry(), AllMirrorFields)
        controller(
            transient,
            linkedManager(autoCopy = true),
            RecordingWriter(
                results = ArrayDeque(listOf(NetworkResult.Failure(NetworkError.NoConnection))),
            ),
        ).drainDue(now = 0L)
        assertEquals(1, transient.state.value.pending)
        assertEquals(1, transient.state.value.withErrors)
    }

    @Test
    fun autoCopyOffDoesNotEnqueueANewSave() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        val linked = linkedManager(autoCopy = false)
        val controller = controller(outbox, linked, RecordingWriter())

        controller.enqueue(
            mediaRef = com.luum.michi.app.core.media.domain.aniListRef(10),
            mediaType = MediaType.ANIME,
            result = com.luum.michi.app.core.medialist.domain.MediaListViewerEntry(
                id = 10,
                status = MediaListStatus.CURRENT,
                progress = 1,
                progressVolumes = null,
                score = 8.5f,
                notes = "note",
                repeat = 1,
                priority = 2,
                isPrivate = false,
                hiddenFromStatusLists = false,
                startedAtMillis = null,
                completedAtMillis = null,
            ),
            fields = AllMirrorFields,
        )

        assertNull(outbox.nextDue(now = 0L))
    }

    @Test
    fun disconnectingOneAccountKeepsTheOtherAccountsQueue() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        outbox.enqueue(
            controllerEntry().copy(sourceProvider = ProviderId.Mal, aniListId = 11),
            AllMirrorFields,
        )
        val linked = linkedManager(autoCopy = true)
        val aniListWriter = RecordingWriter(provider = ProviderId.AniList)
        val controller = controller(
            outbox = outbox,
            linked = linked,
            writer = aniListWriter,
            writers = mapOf(ProviderId.AniList to aniListWriter),
        )

        controller.onAccountDisconnected(ProviderId.Mal)
        controller.drainDue(now = 0L)

        assertEquals(1, aniListWriter.calls.size)
        assertEquals(0, outbox.state.value.pending)
    }

    @Test
    fun preservedOperationsToTheRemainingAccountAreDelivered() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(
            controllerEntry().copy(sourceProvider = ProviderId.Mal, aniListId = 11),
            AllMirrorFields,
        )
        val linked = linkedManager(autoCopy = false)
        val aniListWriter = RecordingWriter(provider = ProviderId.AniList)
        val controller = controller(
            outbox = outbox,
            linked = linked,
            writer = aniListWriter,
            writers = mapOf(ProviderId.AniList to aniListWriter),
        )

        controller.onAccountDisconnected(ProviderId.Mal)
        controller.drainDue(now = 0L)

        assertEquals(1, aniListWriter.calls.size)
        assertEquals(0, outbox.state.value.pending)
    }

    @Test
    fun anExplicitBatchRunsWithAutoCopyOffAndKeepsEachFieldSet() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        val linked = linkedManager(autoCopy = false)
        val writer = RecordingWriter()
        val controller = controller(outbox, linked, writer)

        val started = controller.startBatch(
            listOf(
                MirrorBatchItem(controllerEntry(), setOf(MirrorField.PROGRESS)),
                MirrorBatchItem(controllerEntry().copy(aniListId = 11), AllMirrorFields),
            ),
        )

        assertTrue(started)
        controller.drainDue(now = 0L)
        assertEquals(0, outbox.state.value.pending)
        assertEquals(2, writer.calls.size)
        assertEquals(setOf(MirrorField.PROGRESS), writer.calls[0].second)
        assertEquals(AllMirrorFields, writer.calls[1].second)
        assertFalse(outbox.state.value.batch?.active == true)
    }

    @Test
    fun aPrivateResultIsNeverEnqueued() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        val writer = RecordingWriter()
        val controller = controller(outbox, linkedManager(autoCopy = true), writer)
        val privateEntry = controllerEntry().copy(isPrivate = true)

        controller.enqueue(
            mediaRef = com.luum.michi.app.core.media.domain.aniListRef(10),
            mediaType = MediaType.ANIME,
            result = com.luum.michi.app.core.medialist.domain.MediaListViewerEntry(
                id = 10,
                status = MediaListStatus.CURRENT,
                progress = 1,
                progressVolumes = null,
                score = 8.5f,
                notes = "note",
                repeat = 1,
                priority = 2,
                isPrivate = privateEntry.isPrivate,
                hiddenFromStatusLists = false,
                startedAtMillis = null,
                completedAtMillis = null,
            ),
            fields = AllMirrorFields,
        )

        assertNull(outbox.nextDue(now = 0L))
    }
}
