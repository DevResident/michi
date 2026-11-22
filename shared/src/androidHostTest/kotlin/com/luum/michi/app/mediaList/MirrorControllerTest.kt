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
import com.luum.michi.app.core.medialist.repository.MirrorOutbox
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.mediaList.repository.mirror.MirrorController
import com.luum.michi.app.mediaList.repository.mirror.MirrorWriter
import com.luum.michi.app.testLinkedAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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

private suspend fun linkedManager(autoCopy: Boolean): LinkedAccountManager {
    val provider = TestAuthProvider(id = ProviderId.Mal)
    val manager = testLinkedAccountManager(
        providers = listOf(provider),
        tokenStorages = mapOf(ProviderId.Mal to InMemoryTokenStorage()),
        store = InMemoryLinkedAccountStore(),
        viewerFetchers = mapOf(
            ProviderId.Mal to {
                NetworkResult.Success(Viewer(id = 42, name = "mal", provider = ProviderId.Mal))
            },
        ),
    )
    manager.link(provider, token())
    manager.setAutoCopy(autoCopy)
    return manager
}

private fun controller(
    outbox: MirrorOutbox,
    linked: LinkedAccountManager,
    writer: MirrorWriter,
    authenticated: Boolean = true,
    onLinkDead: suspend () -> Unit = {},
) = MirrorController(
    outbox = outbox,
    linkedAccounts = linked,
    writers = mapOf(writer.provider to writer),
    isSessionAuthenticated = { authenticated },
    activeProvider = { ProviderId.AniList },
    sourceScoreFormat = { ScoreFormat.POINT_10_DECIMAL },
    onLinkDead = onLinkDead,
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
    fun unauthorizedUnlinksAndClearsTheOutbox() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val linked = linkedManager(autoCopy = true)
        val writer = RecordingWriter(
            results = ArrayDeque(listOf(NetworkResult.Failure(NetworkError.Unauthorized(null)))),
        )
        var linkDead = 0

        controller(outbox, linked, writer, onLinkDead = { linkDead++ }).drainDue(now = 0L)

        assertEquals(1, linkDead)
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
    fun unlinkingClearsTheOutboxThroughTheManagerCallback() = runBlocking {
        val outbox = MirrorOutbox(InMemoryFileStore())
        outbox.load()
        outbox.enqueue(controllerEntry(), AllMirrorFields)
        val provider = TestAuthProvider(id = ProviderId.Mal)
        val linked = testLinkedAccountManager(
            providers = listOf(provider),
            tokenStorages = mapOf(ProviderId.Mal to InMemoryTokenStorage()),
            store = InMemoryLinkedAccountStore(),
            viewerFetchers = mapOf(
                ProviderId.Mal to {
                    NetworkResult.Success(Viewer(id = 42, name = "mal", provider = ProviderId.Mal))
                },
            ),
            onUnlinked = { outbox.clear() },
        )
        linked.link(provider, token())

        linked.unlink()

        assertEquals(0, outbox.state.value.pending)
        assertNull(outbox.nextDue(now = 0L))
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
