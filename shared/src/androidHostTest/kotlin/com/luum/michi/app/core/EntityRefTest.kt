package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.media.domain.aniListRef
import com.luum.michi.app.core.media.domain.requireAniListId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntityRefTest {

    @Test
    fun aniListRefRoundTripsItsId() {
        assertEquals(42, aniListRef(42).requireAniListId())
    }

    @Test
    fun aForeignProviderFailsLoudly() {
        assertFailsWith<IllegalArgumentException> {
            EntityRef(ProviderId("other"), "42").requireAniListId()
        }
    }

    @Test
    fun aNonNumericIdFailsLoudly() {
        assertFailsWith<IllegalArgumentException> {
            EntityRef(ProviderId.AniList, "abc").requireAniListId()
        }
    }
}
