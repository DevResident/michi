package com.luum.michi.app.core

import com.luum.michi.app.core.auth.domain.AuthToken
import com.luum.michi.app.core.auth.domain.InMemoryTokenStorage
import com.luum.michi.app.core.auth.domain.TokenStorage
import com.luum.michi.app.core.auth.domain.currentEpochSeconds
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.session.domain.LegacySessionTokenMigration
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
private class SilentFailSessionStorage : TokenStorage {
    private val state = MutableStateFlow<AuthToken?>(null)
    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) = Unit
    override suspend fun load(): AuthToken? = state.value
    override suspend fun clear() {
        state.value = null
    }
}

class LegacySessionTokenMigrationTest {

    private fun migration(
        session: Map<ProviderId, TokenStorage>,
        legacy: Map<ProviderId, TokenStorage>,
    ) = LegacySessionTokenMigration(
        providers = legacy.keys,
        sessionStorages = session,
        legacySessionStorages = legacy,
    )

    @Test
    fun aniListSessionMovesToTheNewStorage() = runBlocking {
        val old = InMemoryTokenStorage().apply { save(token("ani")) }
        val target = InMemoryTokenStorage()

        migration(
            session = mapOf(ProviderId.AniList to target),
            legacy = mapOf(ProviderId.AniList to old),
        ).migrate()

        assertEquals("ani", target.load()?.accessToken)
        assertNull(old.load())
    }

    @Test
    fun malSessionMovesToTheNewStorage() = runBlocking {
        val old = InMemoryTokenStorage().apply { save(token("mal")) }
        val target = InMemoryTokenStorage()

        migration(
            session = mapOf(ProviderId.Mal to target),
            legacy = mapOf(ProviderId.Mal to old),
        ).migrate()

        assertEquals("mal", target.load()?.accessToken)
        assertNull(old.load())
    }

    @Test
    fun bothAccountsMigrateIndependently() = runBlocking {
        val oldAniList = InMemoryTokenStorage().apply { save(token("ani")) }
        val oldMal = InMemoryTokenStorage().apply { save(token("mal")) }
        val aniList = InMemoryTokenStorage()
        val mal = InMemoryTokenStorage()

        migration(
            session = mapOf(ProviderId.AniList to aniList, ProviderId.Mal to mal),
            legacy = mapOf(ProviderId.AniList to oldAniList, ProviderId.Mal to oldMal),
        ).migrate()

        assertEquals("ani", aniList.load()?.accessToken)
        assertEquals("mal", mal.load()?.accessToken)
        assertNull(oldAniList.load())
        assertNull(oldMal.load())
    }

    @Test
    fun noLegacyKeysIsANoOp() = runBlocking {
        val aniList = InMemoryTokenStorage()
        val mal = InMemoryTokenStorage()

        migration(
            session = mapOf(ProviderId.AniList to aniList, ProviderId.Mal to mal),
            legacy = mapOf(
                ProviderId.AniList to InMemoryTokenStorage(),
                ProviderId.Mal to InMemoryTokenStorage(),
            ),
        ).migrate()

        assertNull(aniList.load())
        assertNull(mal.load())
    }

    @Test
    fun theExistingTargetTokenWinsAndTheOldKeysAreCleared() = runBlocking {
        val old = InMemoryTokenStorage().apply { save(token("old")) }
        val target = InMemoryTokenStorage().apply { save(token("current")) }

        migration(
            session = mapOf(ProviderId.AniList to target),
            legacy = mapOf(ProviderId.AniList to old),
        ).migrate()

        assertEquals("current", target.load()?.accessToken)
        assertNull(old.load())
    }

    @Test
    fun aSilentSaveFailureKeepsTheOldKeys() = runBlocking {
        val old = InMemoryTokenStorage().apply { save(token("ani")) }
        val target = SilentFailSessionStorage()

        migration(
            session = mapOf(ProviderId.AniList to target),
            legacy = mapOf(ProviderId.AniList to old),
        ).migrate()

        assertEquals("ani", old.load()?.accessToken, "the old keys must survive a failed save")
        assertNull(target.load())
    }

    @Test
    fun aSecondRunIsIdempotent() = runBlocking {
        val old = InMemoryTokenStorage().apply { save(token("ani")) }
        val target = InMemoryTokenStorage()

        val migration = migration(
            session = mapOf(ProviderId.AniList to target),
            legacy = mapOf(ProviderId.AniList to old),
        )
        migration.migrate()
        migration.migrate()

        assertEquals("ani", target.load()?.accessToken)
        assertNull(old.load())
    }
}
