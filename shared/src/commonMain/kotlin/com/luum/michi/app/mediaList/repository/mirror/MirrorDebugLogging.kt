package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult

/**
 * Debug labels for mirror logs. They carry only the provider/error kind: never
 * payloads, titles, notes, tokens or other personal data.
 */
internal fun NetworkError.debugLabel(): String = when (this) {
    NetworkError.NoConnection -> "no-connection"
    is NetworkError.Http -> "http-$status"
    is NetworkError.Unauthorized -> "unauthorized"
    is NetworkError.GraphQL -> "graphql"
    is NetworkError.NotFound -> "not-found"
    is NetworkError.Unknown -> "unknown"
    is NetworkError.RateLimited -> "rate-limited"
    NetworkError.EntryNotOnProvider -> "not-on-provider"
    NetworkError.LinkExpired -> "link-expired"
}

internal fun NetworkResult<*>.debugLabel(): String = when (this) {
    is NetworkResult.Success -> "ok"
    is NetworkResult.Failure -> "failed ${error.debugLabel()}"
}
