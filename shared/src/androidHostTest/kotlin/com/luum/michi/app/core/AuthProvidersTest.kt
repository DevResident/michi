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
    fun releaseRegistersOnlyAniList() {
        assertEquals(
            listOf(ProviderId.AniList),
            buildAuthProviders(
                isDebug = false,
                isMalConfigured = true,
                aniList = { aniList },
                mal = { mal },
            ).map { it.id },
        )
    }

    @Test
    fun debugWithoutAClientIdRegistersOnlyAniList() {
        assertEquals(
            listOf(ProviderId.AniList),
            buildAuthProviders(
                isDebug = true,
                isMalConfigured = false,
                aniList = { aniList },
                mal = { mal },
            ).map { it.id },
        )
    }

    @Test
    fun debugWithAClientIdAppendsMalAfterAniList() {
        assertEquals(
            listOf(ProviderId.AniList, ProviderId.Mal),
            buildAuthProviders(
                isDebug = true,
                isMalConfigured = true,
                aniList = { aniList },
                mal = { mal },
            ).map { it.id },
        )
    }
}
