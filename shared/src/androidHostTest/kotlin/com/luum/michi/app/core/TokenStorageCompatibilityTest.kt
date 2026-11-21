package com.luum.michi.app.core

import android.content.SharedPreferences
import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.repository.SharedPreferencesTokenStorage
import com.luum.michi.app.core.auth.repository.tokenPrefsName
import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Minimal in-memory [SharedPreferences] for the storage compatibility test. */
private class FakeSharedPreferences(
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {

    val values: MutableMap<String, Any?> = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { changes[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { changes[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { changes[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { changes[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { changes[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { changes[key] = value }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach(values::remove)
            values.putAll(changes)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

class TokenStorageCompatibilityTest {

    @Test
    fun aV1TokenStillLoadsWithTheSameKeys() {
        val prefs = FakeSharedPreferences(
            mapOf(
                "access_token" to "old-access",
                "token_type" to "Bearer",
                "expires_in_seconds" to 3600L,
                "saved_at_epoch_seconds" to 123L,
            ),
        )
        val storage = SharedPreferencesTokenStorage(prefs)

        val token = runBlocking { storage.load() }

        assertEquals("old-access", token?.accessToken)
        assertEquals("Bearer", token?.tokenType)
        assertEquals(3600L, token?.expiresInSeconds)
        assertEquals(123L, token?.savedAtEpochSeconds)
        assertNull(token?.refreshToken, "v1 had no refresh token")
    }

    @Test
    fun aniListKeepsItsExactPrefsFileName() {
        assertEquals("michi_anilist_auth", tokenPrefsName(ProviderId.AniList))
        assertEquals("michi_auth_mal", tokenPrefsName(ProviderId.Mal))
        assertEquals("michi_auth_other", tokenPrefsName(ProviderId("other")))
    }

    @Test
    fun theRefreshTokenIsWrittenAndRemoved() {
        val prefs = FakeSharedPreferences()
        val storage = SharedPreferencesTokenStorage(prefs)

        runBlocking {
            storage.save(AuthToken(accessToken = "a", savedAtEpochSeconds = 1L, refreshToken = "r"))
            assertEquals("r", prefs.values["refresh_token"])

            storage.save(AuthToken(accessToken = "a", savedAtEpochSeconds = 1L, refreshToken = null))
            assertFalse(prefs.contains("refresh_token"))
        }
    }
}
