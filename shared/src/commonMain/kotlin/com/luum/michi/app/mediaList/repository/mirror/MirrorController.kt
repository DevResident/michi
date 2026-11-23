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
import com.luum.michi.app.core.medialist.repository.MirrorBatchItem
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
 * The automatic mirror: turns a successful followed-account save into an outbox
 * operation and drains it in the background. It never throws into the primary
 * save path.
 *
 * Pending operations keep their destination: after a followed switch they are
 * still delivered to the account they were queued for. Only a disconnect drops
 * the operations aimed at that account.
 */
internal class MirrorController(
    private val outbox: MirrorOutbox,
    private val linkedAccounts: LinkedAccountManager,
    private val writers: Map<ProviderId, MirrorWriter>,
    private val followedProvider: () -> ProviderId?,
    private val sourceScoreFormat: () -> ScoreFormat,
    private val isConnected: (ProviderId) -> Boolean,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { currentEpochSeconds() * 1000L },
) : MirrorSink {

    val state: StateFlow<MirrorOutboxState> = outbox.state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /**
     * Set by a disconnect: queued changes aimed at the account that stays must
     * be delivered even though the toggle was turned off with the disconnect.
     */
    private var deliverPreserved = false

    /** Called once after session + linked-account bootstrap. */
    suspend fun start() {
        outbox.load()
        if (followedProvider() == null) {
            outbox.clear()
            return
        }
        if (linkedAccounts.autoCopy.value) {
            resume()
        } else if (outbox.state.value.pending > 0) {
            deliverPreserved = true
            resume()
        }
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
     * Explicit "Sync now": it runs even with automatic copies off. Each item
     * carries its own field set; Add rows use all fields, Update rows only the
     * fields the user saw in the preview.
     */
    suspend fun startBatch(items: List<MirrorBatchItem>): Boolean {
        if (items.isEmpty() || followedProvider() == null) return false
        val other = linkedAccounts.linked.value?.provider ?: return false
        val valid = items.filter { item ->
            val target = targetOf(item.entry.sourceProvider)
            target == other && item.fields.isNotEmpty()
        }
        if (valid.isEmpty()) return false
        outbox.beginBatch(valid)
        resume()
        return true
    }

    /** The toggle off pauses automatic work, but never interrupts an explicit batch. */
    fun onAutoCopyChanged(enabled: Boolean) {
        if (enabled) {
            resume()
        } else if (outbox.state.value.batch?.active != true) {
            pause()
        }
    }

    /** A followed switch flips the direction; queued operations keep their target. */
    fun onFollowedChanged() {
        if (linkedAccounts.autoCopy.value) resume()
    }

    /**
     * Drops only the operations aimed at [provider]; the remaining account's
     * queued changes are delivered even though the toggle is now off.
     */
    suspend fun onAccountDisconnected(provider: ProviderId) {
        outbox.discardTarget(provider)
        deliverPreserved = true
        if (followedProvider() != null) resume() else pause()
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
        if (fields.isEmpty()) return
        val source = followedProvider() ?: return
        if (!linkedAccounts.autoCopy.value) return
        val target = linkedAccounts.linked.value?.provider ?: return
        if (target != targetOf(source)) return
        if (source == ProviderId.AniList && result.isPrivate) return
        val aniListId = mediaRef.id.toIntOrNull() ?: return
        if (target == ProviderId.AniList && aniListId <= 0) return
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
        if (followedProvider() == null) {
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
                if (outbox.state.value.pending == 0) deliverPreserved = false
                val next = outbox.state.value.nextAttemptAtEpochMillis
                if (next == null) {
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
        followedProvider() != null &&
            (linkedAccounts.autoCopy.value ||
                outbox.state.value.batch?.active == true ||
                deliverPreserved)

    private suspend fun drainOne(operation: MirrorOutboxOperation) {
        if (followedProvider() == null) {
            outbox.clear()
            return
        }
        if (!isConnected(operation.targetProvider)) {
            outbox.markPermanent(operation, "Account disconnected")
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
                    // The provider client already reports the 401 to the session;
                    // this account's queued copies are not retried.
                    outbox.discardTarget(operation.targetProvider)
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

    private fun targetOf(source: ProviderId): ProviderId =
        if (source == ProviderId.AniList) ProviderId.Mal else ProviderId.AniList

    private fun Long?.toMirrorDate(): MirrorDate? = this?.let { millis ->
        val parts = millisToCalendarParts(millis)
        MirrorDate(year = parts.year, month = parts.month, day = parts.day)
    }

    private companion object {
        const val IdlePollMillis = 60_000L
    }
}
