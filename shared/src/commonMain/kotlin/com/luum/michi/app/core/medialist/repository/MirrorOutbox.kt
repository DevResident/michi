package com.luum.michi.app.core.medialist.repository

import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.parseMediaListStatus
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MirrorOutboxFileName = "mirror_outbox_v1.json"

/** One coalesced copy, persisted across app launches. */
internal data class MirrorOutboxOperation(
    val entry: MirrorEntry,
    val targetProvider: ProviderId,
    val fields: Set<MirrorField>,
    val attempts: Int = 0,
    val lastError: String? = null,
    val firstFailureAtEpochMillis: Long? = null,
    val nextAttemptAtEpochMillis: Long = 0L,
    /** Bumped by every coalesced change; a stale drain result never removes it. */
    val version: Long = 1L,
)

internal data class MirrorOutboxState(
    val pending: Int = 0,
    val withErrors: Int = 0,
    val dropped: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
)

/**
 * Stores one coalesced operation per media. A later save merges its selected
 * fields into the pending one (last value per field); it never replaces fields
 * it did not touch.
 */
internal class MirrorOutbox(
    private val files: FileStore,
    private val json: Json = AniListJson,
    private val nowMillis: () -> Long = { currentEpochSeconds() * 1000L },
) {
    private val mutex = Mutex()
    private val operations = linkedMapOf<Int, MirrorOutboxOperation>()
    private var dropped = 0
    private var loaded = false

    private val _state = MutableStateFlow(MirrorOutboxState())
    val state: StateFlow<MirrorOutboxState> = _state.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        val raw = try {
            files.read(MirrorOutboxFileName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        val dto = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<MirrorOutboxFileDto>(it) }.getOrNull() }
            ?: MirrorOutboxFileDto()
        dropped = dto.dropped.coerceAtLeast(0)
        dto.operations.forEach { op ->
            val resolved = op.toDomainOrNull() ?: return@forEach
            operations[resolved.entry.aniListId] = resolved
        }
        publishLocked()
    }

    suspend fun enqueue(entry: MirrorEntry, fields: Set<MirrorField>) {
        if (fields.isEmpty()) return
        mutex.withLock {
            val key = entry.aniListId
            val existing = operations[key]
            val merged = if (existing == null) {
                MirrorOutboxOperation(
                    entry = entry,
                    targetProvider = entry.targetProvider(),
                    fields = fields,
                    version = 1L,
                )
            } else {
                existing.copy(
                    entry = existing.entry.mergeFrom(entry, fields),
                    fields = existing.fields + fields,
                    attempts = 0,
                    lastError = null,
                    firstFailureAtEpochMillis = null,
                    nextAttemptAtEpochMillis = 0L,
                    version = existing.version + 1L,
                )
            }
            operations[key] = merged
            persistLocked()
            publishLocked()
        }
    }

    suspend fun nextDue(now: Long = nowMillis()): MirrorOutboxOperation? = mutex.withLock {
        operations.values.firstOrNull { it.nextAttemptAtEpochMillis <= now }
    }

    /** Removes only the version that was actually written. */
    suspend fun markSuccess(operation: MirrorOutboxOperation) = mutex.withLock {
        val current = operations[operation.entry.aniListId] ?: return@withLock
        if (current.version != operation.version) return@withLock
        operations.remove(operation.entry.aniListId)
        persistLocked()
        publishLocked()
    }

    suspend fun markTransient(operation: MirrorOutboxOperation, error: String) = mutex.withLock {
        val current = operations[operation.entry.aniListId] ?: return@withLock
        if (current.version != operation.version) return@withLock
        val now = nowMillis()
        val attempts = current.attempts + 1
        val firstFailure = current.firstFailureAtEpochMillis ?: now
        val exhausted = attempts >= MaxAttempts ||
            now - firstFailure >= RetryWindowMillis
        val backoff = if (exhausted) {
            Long.MAX_VALUE
        } else {
            val exponent = (attempts - 1).coerceIn(0, 10)
            val delay = (BaseBackoffMillis shl exponent).coerceAtMost(MaxBackoffMillis)
            now + delay
        }
        operations[current.entry.aniListId] = current.copy(
            attempts = attempts,
            lastError = error,
            firstFailureAtEpochMillis = firstFailure,
            nextAttemptAtEpochMillis = backoff,
        )
        persistLocked()
        publishLocked()
    }

    suspend fun markPermanent(operation: MirrorOutboxOperation, error: String) = mutex.withLock {
        val current = operations[operation.entry.aniListId] ?: return@withLock
        if (current.version != operation.version) return@withLock
        operations.remove(operation.entry.aniListId)
        dropped += 1
        persistLocked()
        publishLocked()
    }

    /** Re-arms every parked/failed operation and asks the drainer to try now. */
    suspend fun retryFailed() = mutex.withLock {
        var changed = false
        for ((key, op) in operations) {
            if (op.lastError == null) continue
            operations[key] = op.copy(
                attempts = 0,
                lastError = null,
                firstFailureAtEpochMillis = null,
                nextAttemptAtEpochMillis = 0L,
                version = op.version + 1L,
            )
            changed = true
        }
        if (changed) {
            persistLocked()
            publishLocked()
        }
    }

    suspend fun clear() = mutex.withLock {
        operations.clear()
        dropped = 0
        loaded = true
        try {
            files.delete(MirrorOutboxFileName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Best-effort privacy wipe.
        }
        publishLocked()
    }

    private suspend fun persistLocked() {
        val dto = MirrorOutboxFileDto(
            dropped = dropped,
            operations = operations.values.map { it.toDto() },
        )
        val raw = try {
            json.encodeToString(MirrorOutboxFileDto.serializer(), dto)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        try {
            files.write(MirrorOutboxFileName, raw)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The in-memory state stays usable; a later write retries.
        }
    }

    private fun publishLocked() {
        _state.value = MirrorOutboxState(
            pending = operations.size,
            withErrors = operations.values.count { it.lastError != null },
            dropped = dropped,
            nextAttemptAtEpochMillis = operations.values
                .minOfOrNull { it.nextAttemptAtEpochMillis }
                ?.takeIf { it != Long.MAX_VALUE },
        )
    }

    private fun MirrorEntry.targetProvider(): ProviderId =
        if (sourceProvider == ProviderId.AniList) ProviderId.Mal else ProviderId.AniList

    private fun MirrorEntry.mergeFrom(new: MirrorEntry, fields: Set<MirrorField>): MirrorEntry = copy(
        status = if (MirrorField.STATUS in fields) new.status else status,
        progress = if (MirrorField.PROGRESS in fields) new.progress else progress,
        progressVolumes = if (MirrorField.PROGRESS_VOLUMES in fields) {
            new.progressVolumes
        } else {
            progressVolumes
        },
        score = if (MirrorField.SCORE in fields) new.score else score,
        scoreFormat = if (MirrorField.SCORE in fields) new.scoreFormat else scoreFormat,
        repeat = if (MirrorField.REPEAT in fields) new.repeat else repeat,
        priority = if (MirrorField.PRIORITY in fields) new.priority else priority,
        notes = if (MirrorField.NOTES in fields) new.notes else notes,
        isRewatching = if (MirrorField.STATUS in fields) new.isRewatching else isRewatching,
        startedAt = if (MirrorField.STARTED_AT in fields) new.startedAt else startedAt,
        completedAt = if (MirrorField.COMPLETED_AT in fields) new.completedAt else completedAt,
        updatedAt = new.updatedAt ?: updatedAt,
    )

    private companion object {
        const val MaxAttempts = 10
        const val RetryWindowMillis = 24L * 60L * 60L * 1000L
        const val BaseBackoffMillis = 30_000L
        const val MaxBackoffMillis = 30L * 60L * 1000L
    }
}

@Serializable
private data class MirrorOutboxFileDto(
    val version: Int = 1,
    val dropped: Int = 0,
    val operations: List<MirrorOutboxOperationDto> = emptyList(),
)

@Serializable
private data class MirrorOutboxOperationDto(
    val sourceProvider: String,
    val targetProvider: String,
    val aniListId: Int,
    val malId: Int? = null,
    val type: String,
    val status: String? = null,
    val progress: Int = 0,
    val progressVolumes: Int? = null,
    val score: Float = 0f,
    val scoreFormat: String,
    val repeat: Int = 0,
    val priority: Int = 0,
    val notes: String = "",
    val isPrivate: Boolean = false,
    val isRewatching: Boolean = false,
    val startedAtYear: Int? = null,
    val startedAtMonth: Int? = null,
    val startedAtDay: Int? = null,
    val completedAtYear: Int? = null,
    val completedAtMonth: Int? = null,
    val completedAtDay: Int? = null,
    val updatedAt: Long? = null,
    val fields: List<String>,
    val attempts: Int = 0,
    val lastError: String? = null,
    val firstFailureAtEpochMillis: Long? = null,
    val nextAttemptAtEpochMillis: Long = 0L,
    val version: Long = 1L,
) {
    fun toDomainOrNull(): MirrorOutboxOperation? {
        val source = ProviderId(sourceProvider)
        val target = ProviderId(targetProvider)
        if (source.value.isBlank() || target.value.isBlank() || source == target) return null
        val mediaType = MediaType.entries.firstOrNull { it.name == type } ?: return null
        val format = ScoreFormat.entries.firstOrNull { it.name == scoreFormat } ?: return null
        val resolvedFields = fields.mapNotNull { raw ->
            MirrorField.entries.firstOrNull { it.name == raw }
        }.toSet()
        if (resolvedFields.isEmpty()) return null
        val started = startedAtYear?.let { MirrorDate(it, startedAtMonth, startedAtDay) }
        val completed = completedAtYear?.let { MirrorDate(it, completedAtMonth, completedAtDay) }
        return MirrorOutboxOperation(
            entry = MirrorEntry(
                sourceProvider = source,
                aniListId = aniListId,
                malId = malId,
                type = mediaType,
                status = parseMediaListStatus(status),
                progress = progress,
                progressVolumes = progressVolumes,
                score = score,
                scoreFormat = format,
                repeat = repeat,
                priority = priority,
                notes = notes,
                isPrivate = isPrivate,
                isRewatching = isRewatching,
                startedAt = started,
                completedAt = completed,
                updatedAt = updatedAt,
            ),
            targetProvider = target,
            fields = resolvedFields,
            attempts = attempts,
            lastError = lastError,
            firstFailureAtEpochMillis = firstFailureAtEpochMillis,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            version = version,
        )
    }
}

private fun MirrorOutboxOperation.toDto(): MirrorOutboxOperationDto = MirrorOutboxOperationDto(
    sourceProvider = entry.sourceProvider.value,
    targetProvider = targetProvider.value,
    aniListId = entry.aniListId,
    malId = entry.malId,
    type = entry.type.name,
    status = entry.status?.name,
    progress = entry.progress,
    progressVolumes = entry.progressVolumes,
    score = entry.score,
    scoreFormat = entry.scoreFormat.name,
    repeat = entry.repeat,
    priority = entry.priority,
    notes = entry.notes,
    isPrivate = entry.isPrivate,
    isRewatching = entry.isRewatching,
    startedAtYear = entry.startedAt?.year,
    startedAtMonth = entry.startedAt?.month,
    startedAtDay = entry.startedAt?.day,
    completedAtYear = entry.completedAt?.year,
    completedAtMonth = entry.completedAt?.month,
    completedAtDay = entry.completedAt?.day,
    updatedAt = entry.updatedAt,
    fields = fields.map { it.name },
    attempts = attempts,
    lastError = lastError,
    firstFailureAtEpochMillis = firstFailureAtEpochMillis,
    nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
    version = version,
)
