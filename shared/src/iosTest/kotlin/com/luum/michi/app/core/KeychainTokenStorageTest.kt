package com.luum.michi.app.core

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.auth.repository.KeychainAccount
import com.luum.michi.app.core.auth.repository.KeychainBackend
import com.luum.michi.app.core.auth.repository.KeychainInstallGuard
import com.luum.michi.app.core.auth.repository.KeychainServiceAniList
import com.luum.michi.app.core.auth.repository.KeychainServiceMal
import com.luum.michi.app.core.auth.repository.KeychainTokenStorage
import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeKeychainBackend : KeychainBackend {
    private val items = mutableMapOf<Pair<String, String>, ByteArray>()

    var failWrites = false
    var failDeletes = false

    override fun read(service: String, account: String): ByteArray? = items[service to account]

    override fun write(service: String, account: String, data: ByteArray): Boolean {
        if (failWrites) return false
        items[service to account] = data
        return true
    }

    override fun delete(service: String, account: String): Boolean {
        if (failDeletes) return false
        items.remove(service to account)
        return true
    }

    fun stored(service: String, account: String = KeychainAccount): ByteArray? =
        items[service to account]
}

private fun token(value: String = "access") = AuthToken(
    accessToken = value,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
    refreshToken = "refresh",
)

private fun storage(backend: FakeKeychainBackend) = KeychainTokenStorage(
    provider = ProviderId.AniList,
    backend = backend,
    service = "com.luum.michi.app.test.keychain",
)

class KeychainTokenStorageTest {

    @Test
    fun saveLoadAndClearRoundTrip() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)
        val value = token()

        storage.save(value)
        assertEquals(value, storage.load())

        storage.clear()
        assertNull(storage.load())
        assertNull(backend.stored(storageService))
    }

    @Test
    fun saveUpdatesAnExistingItem() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)

        storage.save(token("first"))
        storage.save(token("second"))

        assertEquals("second", storage.load()?.accessToken)
    }

    @Test
    fun clearTreatsAMissingItemAsSuccess() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)

        storage.clear()

        assertNull(storage.load())
    }

    @Test
    fun aFailedDeleteLeavesATombstoneThatLoadsAsNull() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)
        storage.save(token("secret"))
        backend.failDeletes = true

        storage.clear()

        assertNull(storage.load(), "the tombstone must hide the old token")
        assertNotNull(backend.stored(storageService))
        assertEquals(0, backend.stored(storageService)?.size)
    }

    @Test
    fun emptyAndCorruptDataLoadAsNull() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)

        backend.write(storageService, KeychainAccount, ByteArray(0))
        assertNull(storage.load())

        backend.write(storageService, KeychainAccount, byteArrayOf(1, 2, 3))
        assertNull(storage.load())
    }

    @Test
    fun aSilentWriteFailureDoesNotChangeTheLoadedToken() = runBlocking {
        val backend = FakeKeychainBackend()
        val storage = storage(backend)
        storage.save(token("kept"))
        backend.failWrites = true

        storage.save(token("lost"))

        assertEquals("kept", storage.load()?.accessToken)
    }
}

class KeychainInstallGuardTest {

    @Test
    fun aFreshInstallClearsTheKnownServicesOnce() {
        val backend = FakeKeychainBackend()
        val suite = "michi.test.keychain.${NSUUID.UUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        defaults.removePersistentDomainForName(suite)
        backend.write(KeychainServiceAniList, KeychainAccount, byteArrayOf(1))
        backend.write(KeychainServiceMal, KeychainAccount, byteArrayOf(2))

        KeychainInstallGuard.clearOnFreshInstall(
            defaults = defaults,
            backend = backend,
            services = listOf(KeychainServiceAniList, KeychainServiceMal),
        )

        assertNull(backend.stored(KeychainServiceAniList))
        assertNull(backend.stored(KeychainServiceMal))
        assertTrue(defaults.boolForKey(KeychainInstallGuard.MarkerKey))

        backend.write(KeychainServiceAniList, KeychainAccount, byteArrayOf(3))
        KeychainInstallGuard.clearOnFreshInstall(
            defaults = defaults,
            backend = backend,
            services = listOf(KeychainServiceAniList, KeychainServiceMal),
        )

        assertNotNull(backend.stored(KeychainServiceAniList), "the marker protects later launches")
        defaults.removePersistentDomainForName(suite)
    }
}

private const val storageService = "com.luum.michi.app.test.keychain"
