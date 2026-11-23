package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.mal.domain.MalEntryStatus
import com.luum.michi.app.core.mal.domain.MalEntryUpdate
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The shared read/write gateway for one account's MAL entries. It also keeps
 * the last read of each entry **in memory**, so a save only rewrites what the
 * user actually changed:
 * - MAL allows partial dates (`YYYY`, `YYYY-MM`) while the editor only holds
 *   full millis. When the incoming date equals the normalized raw date, the
 *   field is omitted from the partial PATCH.
 * - Comments, priority and the rewatch/reread count are omitted when they equal
 *   the last read. If MAL ever leaves them out of a read, the editor shows the
 *   empty default and an untouched save cannot wipe the real value.
 * Without a previous read, an empty date, empty comments, priority 0 and
 * repeat 0 are never sent: nothing is cleared that the user was not shown.
 *
 * [clear] drops the memory; the session calls it with the other user data.
 */
internal class MalEntryClient(
    private val http: MalHttp,
    private val bridge: MediaIdBridge,
    private val tokenProvider: suspend () -> AuthToken?,
    private val onUnauthorized: suspend (AuthToken) -> Unit = {},
) {
    private data class LastRead(
        val start: String?,
        val finish: String?,
        val comments: String,
        val priority: Int,
        val repeat: Int,
    )

    private val lastReads = mutableMapOf<Int, LastRead>()
    private val lastReadsMutex = Mutex()

    /** Forgets every remembered read (logout, 401, account switch). */
    suspend fun clear() {
        lastReadsMutex.withLock { lastReads.clear() }
    }

    suspend fun read(
        aniListId: Int,
        type: MediaType,
        idMal: Int?,
    ): NetworkResult<MalEntryStatus?> {
        val ref = if (idMal != null) {
            MalRef(malId = idMal, type = type).also { bridge.rememberMalRef(aniListId, it) }
        } else {
            when (val resolved = bridge.resolveMal(aniListId)) {
                is NetworkResult.Failure -> return resolved
                is NetworkResult.Success -> resolved.value
                    ?: return NetworkResult.Failure(NetworkError.EntryNotOnProvider)
            }
        }

        val token = tokenProvider() ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
        return when (val result = http.fetchEntry(token, ref)) {
            is NetworkResult.Success -> {
                remember(aniListId, ref.type, result.value)
                result
            }
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                if (result.error is NetworkError.NotFound) {
                    NetworkResult.Failure(NetworkError.EntryNotOnProvider)
                } else {
                    result
                }
            }
        }
    }

    /**
     * A MAL-only work (no AniList media): title, totals and the viewer's entry
     * straight from MAL, keyed by its synthetic id. No bridge, no AniList.
     */
    suspend fun readWork(listId: Int, ref: MalRef): NetworkResult<MalWorkEntry> {
        val token = tokenProvider() ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
        return when (val result = http.fetchWorkEntry(token, ref)) {
            is NetworkResult.Success -> {
                remember(listId, ref.type, result.value.status)
                result
            }
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                if (result.error is NetworkError.NotFound) {
                    NetworkResult.Failure(NetworkError.EntryNotOnProvider)
                } else {
                    result
                }
            }
        }
    }

    suspend fun save(
        aniListId: Int,
        ref: MalRef,
        update: MalEntryUpdate,
    ): NetworkResult<MalEntryStatus> = saveInternal(aniListId, ref, update, exact = false)

    /**
     * Mirror-only save: writes exactly the supplied fields. Unlike [save], an
     * empty date/comments/priority is a deliberate clear, so a full primary
     * entry can be copied faithfully.
     */
    suspend fun saveExact(
        aniListId: Int,
        ref: MalRef,
        update: MalEntryUpdate,
    ): NetworkResult<MalEntryStatus> = saveInternal(aniListId, ref, update, exact = true)

    private suspend fun saveInternal(
        aniListId: Int,
        ref: MalRef,
        update: MalEntryUpdate,
        exact: Boolean,
    ): NetworkResult<MalEntryStatus> {
        val token = tokenProvider() ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
        val adjusted = if (exact) update else omitUntouched(aniListId, update)
        return when (val result = http.patchEntry(token, ref, adjusted)) {
            is NetworkResult.Success -> {
                remember(aniListId, ref.type, result.value)
                result
            }
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                if (result.error is NetworkError.NotFound) {
                    NetworkResult.Failure(NetworkError.EntryNotOnProvider)
                } else {
                    result
                }
            }
        }
    }

    suspend fun delete(aniListId: Int, ref: MalRef): NetworkResult<Unit> {
        val token = tokenProvider() ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
        return when (val result = http.deleteEntry(token, ref)) {
            is NetworkResult.Success -> {
                lastReadsMutex.withLock { lastReads.remove(aniListId) }
                result
            }
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                result
            }
        }
    }

    private suspend fun remember(aniListId: Int, type: MediaType, status: MalEntryStatus?) {
        val read = if (status == null) {
            LastRead(start = null, finish = null, comments = "", priority = 0, repeat = 0)
        } else {
            LastRead(
                start = status.startDate,
                finish = status.finishDate,
                comments = status.comments.orEmpty(),
                priority = status.priority ?: 0,
                repeat = if (type == MediaType.MANGA) status.numTimesReread else status.numTimesRewatched,
            )
        }
        lastReadsMutex.withLock { lastReads[aniListId] = read }
    }

    private suspend fun omitUntouched(aniListId: Int, update: MalEntryUpdate): MalEntryUpdate {
        val read = lastReadsMutex.withLock { lastReads[aniListId] }
            // Unknown state: never clear anything the user was not shown.
            ?: return update.copy(
                startDate = update.startDate?.takeIf { it.isNotEmpty() },
                finishDate = update.finishDate?.takeIf { it.isNotEmpty() },
                comments = update.comments?.takeIf { it.isNotEmpty() },
                priority = update.priority?.takeIf { it != 0 },
                repeat = update.repeat?.takeIf { it != 0 },
            )
        return update.copy(
            startDate = adjustDate(update.startDate, read.start),
            finishDate = adjustDate(update.finishDate, read.finish),
            comments = update.comments?.takeIf { it != read.comments },
            priority = update.priority?.takeIf { it != read.priority },
            repeat = update.repeat?.takeIf { it != read.repeat },
        )
    }

    private fun adjustDate(incoming: String?, raw: String?): String? {
        if (incoming == null) return null
        if (incoming.isEmpty()) {
            // No date before → nothing to clear; a real date before → clear it.
            return if (raw == null) null else ""
        }
        val normalizedRaw = normalizeMalDate(raw)
        return if (normalizedRaw != null && incoming == normalizedRaw) null else incoming
    }
}
