package com.luum.michi.app.core.auth.repository

import android.content.Context
import android.content.SharedPreferences
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KeyAccessToken = "access_token"
private const val KeyTokenType = "token_type"
private const val KeyExpiresInSeconds = "expires_in_seconds"
private const val KeySavedAtEpochSeconds = "saved_at_epoch_seconds"
private const val KeyRefreshToken = "refresh_token"

/**
 * The Android prefs file for one provider. AniList keeps the exact v1.0.0 name
 * so an existing session survives the upgrade; every other provider gets its
 * own file.
 */
internal fun tokenPrefsName(provider: ProviderId): String =
    if (provider == ProviderId.AniList) "michi_anilist_auth" else "michi_auth_${provider.value}"

/**
 * The linked account never shares the session's token file: logout/401/provider
 * switching wipe the session namespace, and a linked provider can never be the
 * active one. Its own file keeps that separation explicit.
 */
internal fun linkedTokenPrefsName(provider: ProviderId): String =
    "michi_link_${provider.value}"

/**
 * Persists one provider's token in `SharedPreferences`. The token is stored in
 * plain text — sufficient for an API that allows the user to revoke tokens at
 * any time. Upgrade to `EncryptedSharedPreferences` for defense in depth.
 */
internal class SharedPreferencesTokenStorage internal constructor(
    private val prefs: SharedPreferences,
) : TokenStorage {

    constructor(
        context: Context,
        provider: ProviderId,
        prefsName: String = tokenPrefsName(provider),
    ) : this(
        context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        ),
    )

    private val state = MutableStateFlow(readToken())

    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) {
        prefs.edit().apply {
            putString(KeyAccessToken, token.accessToken)
            putString(KeyTokenType, token.tokenType)
            token.expiresInSeconds?.let { putLong(KeyExpiresInSeconds, it) }
                ?: remove(KeyExpiresInSeconds)
            putLong(KeySavedAtEpochSeconds, token.savedAtEpochSeconds)
            token.refreshToken?.let { putString(KeyRefreshToken, it) }
                ?: remove(KeyRefreshToken)
        }.apply()
        state.value = token
    }

    override suspend fun load(): AuthToken? = readToken()

    override suspend fun clear() {
        prefs.edit()
            .remove(KeyAccessToken)
            .remove(KeyTokenType)
            .remove(KeyExpiresInSeconds)
            .remove(KeySavedAtEpochSeconds)
            .remove(KeyRefreshToken)
            .apply()
        state.value = null
    }

    private fun readToken(): AuthToken? {
        val accessToken = prefs.getString(KeyAccessToken, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val tokenType = prefs.getString(KeyTokenType, null)?.takeIf { it.isNotBlank() } ?: "Bearer"
        val expiresIn = if (prefs.contains(KeyExpiresInSeconds)) {
            prefs.getLong(KeyExpiresInSeconds, 0L)
        } else {
            null
        }
        val savedAt = prefs.getLong(KeySavedAtEpochSeconds, currentEpochSeconds())
        val refreshToken = prefs.getString(KeyRefreshToken, null)?.takeIf { it.isNotBlank() }
        return AuthToken(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresInSeconds = expiresIn,
            savedAtEpochSeconds = savedAt,
            refreshToken = refreshToken,
        )
    }
}
