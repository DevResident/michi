package com.luum.michi.app.core.auth.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * AniList keeps the exact v1.0.0 NSUserDefaults keys (`michi_anilist_*`) so an
 * existing session survives the upgrade. Every other provider gets its own
 * `michi_auth_{provider}_*` prefix.
 */
private fun tokenKey(provider: ProviderId, suffix: String, keyPrefix: String?): String =
    if (keyPrefix != null) {
        "${keyPrefix}_$suffix"
    } else if (provider == ProviderId.AniList) {
        "michi_anilist_$suffix"
    } else {
        "michi_auth_${provider.value}_$suffix"
    }

/** Linked tokens never share the session's NSUserDefaults keys. */
internal fun linkedTokenKeyPrefix(provider: ProviderId): String = "michi_link_${provider.value}"

/**
 * Persists one provider's token in `NSUserDefaults`. Upgrade to Keychain for
 * defense in depth.
 */
internal class NSUserDefaultsTokenStorage(
    private val provider: ProviderId,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val keyPrefix: String? = null,
) : TokenStorage {

    private val state = MutableStateFlow(readToken())

    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) {
        defaults.setObject(token.accessToken, tokenKey(provider, "access_token", keyPrefix))
        defaults.setObject(token.tokenType, tokenKey(provider, "token_type", keyPrefix))
        if (token.expiresInSeconds != null) {
            defaults.setObject(
                token.expiresInSeconds.toString(),
                tokenKey(provider, "expires_in_seconds", keyPrefix),
            )
        } else {
            defaults.removeObjectForKey(tokenKey(provider, "expires_in_seconds", keyPrefix))
        }
        defaults.setObject(
            token.savedAtEpochSeconds.toString(),
            tokenKey(provider, "saved_at_epoch_seconds", keyPrefix),
        )
        if (token.refreshToken != null) {
            defaults.setObject(token.refreshToken, tokenKey(provider, "refresh_token", keyPrefix))
        } else {
            defaults.removeObjectForKey(tokenKey(provider, "refresh_token", keyPrefix))
        }
        state.value = token
    }

    override suspend fun load(): AuthToken? = readToken()

    override suspend fun clear() {
        defaults.removeObjectForKey(tokenKey(provider, "access_token", keyPrefix))
        defaults.removeObjectForKey(tokenKey(provider, "token_type", keyPrefix))
        defaults.removeObjectForKey(tokenKey(provider, "expires_in_seconds", keyPrefix))
        defaults.removeObjectForKey(tokenKey(provider, "saved_at_epoch_seconds", keyPrefix))
        defaults.removeObjectForKey(tokenKey(provider, "refresh_token", keyPrefix))
        state.value = null
    }

    private fun readToken(): AuthToken? {
        val accessToken = defaults.stringForKey(tokenKey(provider, "access_token", keyPrefix))
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val tokenType = defaults.stringForKey(tokenKey(provider, "token_type", keyPrefix))
            ?.takeIf { it.isNotBlank() }
            ?: "Bearer"
        val expiresIn = defaults.stringForKey(tokenKey(provider, "expires_in_seconds", keyPrefix))
            ?.toLongOrNull()
        val savedAt = defaults.stringForKey(tokenKey(provider, "saved_at_epoch_seconds", keyPrefix))
            ?.toLongOrNull()
            ?: currentEpochSeconds()
        val refreshToken = defaults.stringForKey(tokenKey(provider, "refresh_token", keyPrefix))
            ?.takeIf { it.isNotBlank() }
        return AuthToken(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresInSeconds = expiresIn,
            savedAtEpochSeconds = savedAt,
            refreshToken = refreshToken,
        )
    }
}
