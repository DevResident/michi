package com.luum.michi.app.core.auth.domain

/**
 * A provider's OAuth token. Provider-neutral: the same shape works for the
 * implicit grant (no refresh token) and for providers that refresh.
 */
internal data class AuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long? = null,
    val savedAtEpochSeconds: Long,
    val refreshToken: String? = null,
) {
    val isExpired: Boolean
        get() = expiresWithin(0)

    /** True when the token expires in [seconds] or less; false without an expiry. */
    fun expiresWithin(seconds: Long): Boolean {
        val expiresIn = expiresInSeconds ?: return false
        val nowSeconds = currentEpochSeconds()
        return nowSeconds + seconds >= savedAtEpochSeconds + expiresIn
    }

    val authorizationHeaderValue: String
        get() = "$tokenType $accessToken"
}

internal expect fun currentEpochSeconds(): Long
