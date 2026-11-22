package com.luum.michi.app.core

import com.luum.michi.app.InMemoryLinkedAccountStore
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.testLinkedAccountManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkedAccountManagerTest {

    private fun account() = LinkedAccount(
        provider = ProviderId.Mal,
        viewer = LinkedViewer(
            id = 42,
            name = "mal-user",
            avatarUrl = "https://example.com/avatar.png",
            scoreFormat = ScoreFormat.POINT_10,
        ),
    )

    @Test
    fun bootstrapRestoresIdentityAndPreferences() {
        val store = InMemoryLinkedAccountStore(
            account = account(),
            lostAccess = ProviderId.AniList,
            autoCopy = true,
        )
        val manager = testLinkedAccountManager(store)

        manager.bootstrap()

        assertEquals("mal-user", manager.linked.value?.viewer?.name)
        assertEquals(ProviderId.AniList, manager.lostAccess.value)
        assertTrue(manager.autoCopy.value)
    }

    @Test
    fun setOtherPersistsAndClears() {
        val store = InMemoryLinkedAccountStore()
        val manager = testLinkedAccountManager(store)

        manager.setOther(account())

        assertEquals(ProviderId.Mal, manager.linked.value?.provider)
        assertEquals("mal-user", store.account?.viewer?.name)

        manager.setOther(null)

        assertNull(manager.linked.value)
        assertNull(store.account)
    }

    @Test
    fun preferencesPersist() {
        val store = InMemoryLinkedAccountStore()
        val manager = testLinkedAccountManager(store)

        manager.setAutoCopy(true)
        manager.setLostAccess(ProviderId.Mal)
        manager.onLinkFailed(NetworkError.NoConnection)

        assertTrue(store.autoCopy)
        assertEquals(ProviderId.Mal, store.lostAccess)
        assertEquals(NetworkError.NoConnection, manager.linkError.value)

        manager.clearLinkError()
        manager.dismissLostAccess()
        manager.setAutoCopy(false)

        assertNull(manager.linkError.value)
        assertNull(manager.lostAccess.value)
        assertNull(store.lostAccess)
        assertFalse(store.autoCopy)
    }
}
