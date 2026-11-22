package com.luum.michi.app.core.mal.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.mal.domain.MalEntryStatus
import com.luum.michi.app.core.mal.domain.MalEntryUpdate
import com.luum.michi.app.core.media.domain.MalRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.repository.MediaIdBridge
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult

/**
 * The shared read/write gateway for one account's MAL entries. It also keeps
 * the raw date strings of the last read **in memory**: MAL allows partial dates
 * (`YYYY`, `YYYY-MM`) while the editor only holds full millis, so a save must
 * not rewrite a date the user did not touch. When the incoming date equals the
 * normalized raw date, the field is omitted from the partial PATCH.
 */
internal class MalEntryClient(
    private val http: MalHttp,
    private val bridge: MediaIdBridge,
    private val tokenProvider: suspend () -> AuthToken?,
    private val onUnauthorized: suspend (AuthToken) -> Unit = {},
) {
    private data class RawDates(val start: String?, val finish: String?)

    private val rawDates = mutableMapOf<Int, RawDates>()

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
                rememberRawDates(aniListId, result.value)
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
    ): NetworkResult<MalEntryStatus> {
        val token = tokenProvider() ?: return NetworkResult.Failure(NetworkError.Unauthorized(null))
        val adjusted = adjustUntouchedDates(aniListId, update)
        return when (val result = http.patchEntry(token, ref, adjusted)) {
            is NetworkResult.Success -> {
                rememberRawDates(aniListId, result.value)
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
                rawDates.remove(aniListId)
                result
            }
            is NetworkResult.Failure -> {
                if (result.error is NetworkError.Unauthorized) onUnauthorized(token)
                result
            }
        }
    }

    private fun rememberRawDates(aniListId: Int, status: MalEntryStatus?) {
        rawDates[aniListId] = if (status == null) {
            RawDates(start = null, finish = null)
        } else {
            RawDates(start = status.startDate, finish = status.finishDate)
        }
    }

    private fun adjustUntouchedDates(aniListId: Int, update: MalEntryUpdate): MalEntryUpdate {
        val raw = rawDates[aniListId]
            // Unknown raw state: never clear a date the user was not shown.
            ?: return update.copy(
                startDate = update.startDate?.takeIf { it.isNotEmpty() },
                finishDate = update.finishDate?.takeIf { it.isNotEmpty() },
            )
        return update.copy(
            startDate = adjustDate(update.startDate, raw.start),
            finishDate = adjustDate(update.finishDate, raw.finish),
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
