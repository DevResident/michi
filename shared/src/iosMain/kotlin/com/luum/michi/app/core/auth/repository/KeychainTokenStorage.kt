package com.luum.michi.app.core.auth.repository

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val KeychainServiceAniList = "com.luum.michi.app.auth.anilist"
internal const val KeychainServiceMal = "com.luum.michi.app.auth.mal"
internal const val KeychainAccount = "session"

internal fun keychainService(provider: ProviderId): String =
    if (provider == ProviderId.AniList) KeychainServiceAniList else KeychainServiceMal

/**
 * Persists one provider's token as a single generic-password Keychain item.
 * `AfterFirstUnlockThisDeviceOnly` keeps it out of iCloud/Finder backups and
 * device transfers. An empty item is a tombstone: [load] treats it as absent.
 */
internal class KeychainTokenStorage(
    provider: ProviderId,
    private val backend: KeychainBackend = SecItemKeychainBackend(),
    private val service: String = keychainService(provider),
    private val account: String = KeychainAccount,
) : TokenStorage {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow(readToken())

    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) {
        mutex.withLock {
            val data = json.encodeToString(token.toPayload()).encodeToByteArray()
            if (backend.write(service, account, data)) {
                state.value = token
            }
        }
    }

    override suspend fun load(): AuthToken? = mutex.withLock { readToken() }

    override suspend fun clear() {
        mutex.withLock {
            if (!backend.delete(service, account)) {
                // A failed delete must not leave a token that the next launch
                // would restore: overwrite it with an empty tombstone.
                backend.write(service, account, ByteArray(0))
            }
            state.value = null
        }
    }

    private fun readToken(): AuthToken? {
        val bytes = backend.read(service, account) ?: return null
        if (bytes.isEmpty()) return null
        val payload = runCatching {
            json.decodeFromString<KeychainTokenPayload>(bytes.decodeToString())
        }.getOrNull() ?: return null
        if (payload.accessToken.isBlank()) return null
        return AuthToken(
            accessToken = payload.accessToken,
            tokenType = payload.tokenType.ifBlank { "Bearer" },
            expiresInSeconds = payload.expiresInSeconds,
            savedAtEpochSeconds = payload.savedAtEpochSeconds,
            refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() },
        )
    }

    private fun AuthToken.toPayload() = KeychainTokenPayload(
        accessToken = accessToken,
        tokenType = tokenType,
        expiresInSeconds = expiresInSeconds,
        savedAtEpochSeconds = savedAtEpochSeconds,
        refreshToken = refreshToken,
    )
}

@Serializable
private data class KeychainTokenPayload(
    val version: Int = 1,
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long? = null,
    val savedAtEpochSeconds: Long,
    val refreshToken: String? = null,
)
