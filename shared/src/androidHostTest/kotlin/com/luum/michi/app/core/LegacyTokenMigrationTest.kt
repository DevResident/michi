package com.luum.michi.app.core

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.session.domain.LegacyTokenMigration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun token(value: String) = AuthToken(
    accessToken = value,
    savedAtEpochSeconds = currentEpochSeconds(),
    expiresInSeconds = 3600L,
)

/** Accepts save() without storing anything, like a failing Keychain. */
private class SilentFailTokenStorage : TokenStorage {
    private val state = MutableStateFlow<AuthToken?>(null)
    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) = Unit
    override suspend fun load(): AuthToken? = state.value
    override suspend fun clear() {
        state.value = null
    }
}

class LegacyTokenMigrationTest {

    @Test
    fun aLinkedTokenMovesToItsSessionStorage() = runBlocking {
        val legacyMal = InMemoryTokenStorage().apply { save(token("mal")) }
        val sessionMal = InMemoryTokenStorage()
        val migration = LegacyTokenMigration(
            providers = setOf(ProviderId.Mal),
            sessionStorages = mapOf(ProviderId.Mal to sessionMal),
            legacyStorages = mapOf(ProviderId.Mal to legacyMal),
        )

        migration.migrate()

        assertEquals("mal", sessionMal.load()?.accessToken)
        assertNull(legacyMal.load())
    }

    @Test
    fun theExistingSessionTokenWinsAndTheLegacyIsStillCleared() = runBlocking {
        val legacyMal = InMemoryTokenStorage().apply { save(token("old-linked")) }
        val sessionMal = InMemoryTokenStorage().apply { save(token("current")) }
        val migration = LegacyTokenMigration(
            providers = setOf(ProviderId.Mal),
            sessionStorages = mapOf(ProviderId.Mal to sessionMal),
            legacyStorages = mapOf(ProviderId.Mal to legacyMal),
        )

        migration.migrate()

        assertEquals("current", sessionMal.load()?.accessToken)
        assertNull(legacyMal.load())
    }

    @Test
    fun anEmptyMigrationIsANoOpAndCanRunTwice() = runBlocking {
        val legacyAniList = InMemoryTokenStorage().apply { save(token("ani")) }
        val sessionAniList = InMemoryTokenStorage()
        val migration = LegacyTokenMigration(
            providers = setOf(ProviderId.AniList, ProviderId.Mal),
            sessionStorages = mapOf(ProviderId.AniList to sessionAniList),
            legacyStorages = mapOf(
                ProviderId.AniList to legacyAniList,
                ProviderId.Mal to InMemoryTokenStorage(),
            ),
        )

        migration.migrate()
        migration.migrate()

        assertEquals("ani", sessionAniList.load()?.accessToken)
        assertNull(legacyAniList.load())
    }

    @Test
    fun aSilentSaveFailureKeepsTheLegacyToken() = runBlocking {
        val legacy = InMemoryTokenStorage().apply { save(token("linked")) }
        val session = SilentFailTokenStorage()
        val migration = LegacyTokenMigration(
            providers = setOf(ProviderId.Mal),
            sessionStorages = mapOf(ProviderId.Mal to session),
            legacyStorages = mapOf(ProviderId.Mal to legacy),
        )

        migration.migrate()

        assertEquals("linked", legacy.load()?.accessToken, "the legacy token must survive")
        assertNull(session.load())
    }
}
