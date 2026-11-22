package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorSink
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MirrorOutbox
import com.luum.michi.app.core.medialist.repository.MirrorOutboxOperation
import com.luum.michi.app.core.medialist.repository.MirrorOutboxState
import com.luum.michi.app.core.model.millisToCalendarParts
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The automatic mirror: turns a successful primary save into an outbox
 * operation and drains it in the background. It never throws into the primary
 * save path.
 */
internal class MirrorController(
    private val outbox: MirrorOutbox,
    private val linkedAccounts: LinkedAccountManager,
    private val writers: Map<ProviderId, MirrorWriter>,
    private val isSessionAuthenticated: () -> Boolean,
    private val activeProvider: () -> ProviderId,
    private val sourceScoreFormat: () -> ScoreFormat,
    private val onLinkDead: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { currentEpochSeconds() * 1000L },
) : MirrorSink {

    val state: StateFlow<MirrorOutboxState> = outbox.state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Called once after session + linked-account bootstrap. */
    suspend fun start() {
        outbox.load()
        if (!isSessionAuthenticated() || linkedAccounts.linked.value == null) {
            outbox.clear()
            return
        }
        if (linkedAccounts.autoCopy.value) resume()
    }

    fun resume() {
        wake.trySend(Unit)
        if (job?.isActive == true) return
        job = scope.launch { drainLoop() }
    }

    fun pause() {
        job?.cancel()
        job = null
    }

    suspend fun retryFailed() {
        outbox.retryFailed()
        resume()
    }

    /**
     * Only the selected fields are copied. A private AniList entry never enters
     * the pipeline, and a MAL-only work never targets AniList.
     */
    override suspend fun enqueue(
        mediaRef: EntityRef,
        mediaType: MediaType,
        result: MediaListViewerEntry,
        fields: Set<MirrorField>,
    ) {
        if (fields.isEmpty() || !isSessionAuthenticated()) return
        val linked = linkedAccounts.linked.value ?: return
        if (!linkedAccounts.autoCopy.value) return
        val source = activeProvider()
        val target = if (source == ProviderId.AniList) ProviderId.Mal else ProviderId.AniList
        if (linked.provider != target) return
        if (source == ProviderId.AniList && result.isPrivate) return
        val aniListId = mediaRef.id.toIntOrNull() ?: return
        if (linked.provider == ProviderId.AniList && aniListId <= 0) return
        val status = result.status ?: return
        val entry = MirrorEntry(
            sourceProvider = source,
            aniListId = aniListId,
            malId = null,
            type = mediaType,
            status = status,
            progress = result.progress,
            progressVolumes = result.progressVolumes,
            score = result.score,
            scoreFormat = if (source == ProviderId.Mal) {
                ScoreFormat.POINT_10
            } else {
                sourceScoreFormat()
            },
            repeat = result.repeat,
            priority = result.priority,
            notes = result.notes,
            isPrivate = result.isPrivate,
            isRewatching = result.isRewatching ||
                (source == ProviderId.AniList && status == MediaListStatus.REPEATING),
            startedAt = result.startedAtDate ?: result.startedAtMillis.toMirrorDate(),
            completedAt = result.completedAtDate ?: result.completedAtMillis.toMirrorDate(),
            updatedAt = null,
        )
        outbox.enqueue(entry, fields)
        resume()
    }

    /**
     * Test seam: drains everything currently due at [now]. Production uses the
     * background loop, which also wakes for retries.
     */
    internal suspend fun drainDue(now: Long = nowMillis()) {
        outbox.load()
        if (!isSessionAuthenticated()) {
            outbox.clear()
            return
        }
        while (isEnabled()) {
            val operation = outbox.nextDue(now) ?: break
            drainOne(operation)
        }
    }

    private suspend fun drainLoop() {
        try {
            outbox.load()
            while (isEnabled()) {
                val operation = outbox.nextDue(nowMillis())
                if (operation != null) {
                    drainOne(operation)
                    continue
                }
                val next = outbox.state.value.nextAttemptAtEpochMillis
                if (next == null) {
                    // A parked failed operation waits for an explicit retry; an
                    // idle queue waits for new work, but rechecks periodically
                    // so an unlinked session can end the job.
                    if (outbox.state.value.withErrors > 0) break
                    withTimeoutOrNull(IdlePollMillis) { wake.receive() }
                } else {
                    val wait = (next - nowMillis()).coerceAtLeast(1L)
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The background mirror must never escape into the app scope.
        } finally {
            job = null
        }
    }

    private fun isEnabled(): Boolean =
        isSessionAuthenticated() &&
            linkedAccounts.linked.value != null &&
            linkedAccounts.autoCopy.value

    private suspend fun drainOne(operation: MirrorOutboxOperation) {
        if (!isSessionAuthenticated()) {
            outbox.clear()
            return
        }
        val linked = linkedAccounts.linked.value
        if (linked == null) {
            outbox.clear()
            return
        }
        if (linked.provider != operation.targetProvider) {
            outbox.markPermanent(operation, "Linked provider changed")
            return
        }
        val writer = writers[operation.targetProvider]
        if (writer == null) {
            outbox.markPermanent(operation, "No mirror writer for ${operation.targetProvider.value}")
            return
        }
        val result = try {
            writer.write(operation.entry, operation.fields)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            NetworkResult.Failure(NetworkError.Unknown(throwable))
        }
        when (result) {
            is NetworkResult.Success -> outbox.markSuccess(operation)
            is NetworkResult.Failure -> when {
                result.error is NetworkError.Unauthorized -> {
                    onLinkDead()
                    outbox.clear()
                }
                isPermanent(result.error) -> outbox.markPermanent(operation, result.error.toString())
                else -> outbox.markTransient(operation, result.error.toString())
            }
        }
    }

    private fun isPermanent(error: NetworkError): Boolean = when (error) {
        NetworkError.EntryNotOnProvider -> true
        is NetworkError.NotFound -> true
        is NetworkError.GraphQL -> true
        is NetworkError.Http -> error.status in 400..499 &&
            error.status != 401 && error.status != 429
        else -> false
    }

    private fun Long?.toMirrorDate(): MirrorDate? = this?.let { millis ->
        val parts = millisToCalendarParts(millis)
        MirrorDate(year = parts.year, month = parts.month, day = parts.day)
    }

    private companion object {
        const val IdlePollMillis = 60_000L
    }
}
