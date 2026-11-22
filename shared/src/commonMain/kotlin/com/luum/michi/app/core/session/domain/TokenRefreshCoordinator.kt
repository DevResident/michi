package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The result of one token refresh attempt. */
internal sealed interface RefreshOutcome {
    data class Token(val value: AuthToken) : RefreshOutcome
    data object Unsupported : RefreshOutcome
    data class NetworkFailure(val error: NetworkError) : RefreshOutcome
    data object Closed : RefreshOutcome
    data object Superseded : RefreshOutcome
}

/** The result of persisting a refreshed token. */
internal sealed interface RefreshSaveResult {
    data class Saved(val token: AuthToken) : RefreshSaveResult
    data object Superseded : RefreshSaveResult
    data object Closed : RefreshSaveResult
}

/**
 * One refresh at a time per [key], shared by every concurrent caller.
 *
 * The leader runs in [NonCancellable] so a caller that navigates away never
 * loses a rotated refresh token. Callers are provider-account scoped through
 * [key] (a provider id, or any stable account key).
 */
internal class TokenRefreshCoordinator {

    private data class Entry(val deferred: CompletableDeferred<RefreshOutcome>)

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Any, Entry>()

    /**
     * [loadStored] and [save] are the caller's storage rules: they decide
     * whether a late response is still valid and whether an account still
     * exists. [onInvalidGrant] runs in the leader, under [NonCancellable], so
     * the session/link is closed even if every caller was cancelled.
     */
    suspend fun refresh(
        key: Any,
        token: AuthToken,
        loadStored: suspend () -> AuthToken?,
        refresh: suspend () -> NetworkResult<AuthToken>?,
        save: suspend (AuthToken) -> RefreshSaveResult,
        onInvalidGrant: suspend (AuthToken) -> Unit,
    ): RefreshOutcome {
        val (entry, leader) = mutex.withLock {
            val current = inFlight[key]
            if (current != null) {
                current to false
            } else {
                Entry(CompletableDeferred()).also { inFlight[key] = it } to true
            }
        }
        if (!leader) return entry.deferred.await()

        return withContext(NonCancellable) {
            var outcome: RefreshOutcome = RefreshOutcome.Closed
            try {
                // Another leader may have finished between our load() and here:
                // never spend the old refresh token a second time.
                val stored = loadStored()
                if (stored != null && stored.accessToken != token.accessToken) {
                    outcome = RefreshOutcome.Superseded
                } else {
                    when (val result = refresh()) {
                        null -> outcome = RefreshOutcome.Unsupported
                        is NetworkResult.Success -> {
                            val merged = result.value.copy(
                                refreshToken = result.value.refreshToken ?: token.refreshToken,
                            )
                            outcome = when (val saved = save(merged)) {
                                is RefreshSaveResult.Saved -> RefreshOutcome.Token(saved.token)
                                RefreshSaveResult.Superseded -> RefreshOutcome.Superseded
                                RefreshSaveResult.Closed -> RefreshOutcome.Closed
                            }
                        }
                        is NetworkResult.Failure -> outcome = if (isInvalidGrant(result.error)) {
                            onInvalidGrant(token)
                            RefreshOutcome.Closed
                        } else {
                            RefreshOutcome.NetworkFailure(result.error)
                        }
                    }
                }
            } finally {
                mutex.withLock {
                    if (inFlight[key] === entry) inFlight.remove(key)
                }
                entry.deferred.complete(outcome)
            }
            outcome
        }
    }
}

/** MAL answers a dead refresh token with 400 + `invalid_grant`. */
internal fun isInvalidGrant(error: NetworkError): Boolean = when (error) {
    is NetworkError.Unauthorized -> true
    is NetworkError.Http -> error.body?.contains("invalid_grant", ignoreCase = true) == true
    is NetworkError.GraphQL -> error.messages.any {
        it.contains("invalid_grant", ignoreCase = true)
    }
    else -> false
}
