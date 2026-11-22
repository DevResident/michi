package com.luum.michi.app.core

import com.luum.michi.app.TestAuthProvider
import com.luum.michi.app.buildAuthProviders
import com.luum.michi.app.core.media.domain.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthProvidersTest {

    private val aniList = TestAuthProvider()
    private val mal = TestAuthProvider(id = ProviderId.Mal)

    @Test
    fun withoutAClientIdOnlyAniListIsRegistered() {
        assertEquals(
            listOf(ProviderId.AniList),
            buildAuthProviders(
                isMalConfigured = false,
                aniList = { aniList },
                mal = { mal },
            ).map { it.id },
        )
    }

    @Test
    fun aConfiguredClientIdAppendsMalInReleaseToo() {
        assertEquals(
            listOf(ProviderId.AniList, ProviderId.Mal),
            buildAuthProviders(
                isMalConfigured = true,
                aniList = { aniList },
                mal = { mal },
            ).map { it.id },
        )
    }
}
