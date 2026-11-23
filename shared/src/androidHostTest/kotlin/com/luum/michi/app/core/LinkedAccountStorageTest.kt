package com.luum.michi.app.core

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.session.domain.PendingLink
import com.luum.michi.app.core.session.repository.SettingsStoreLinkedAccountStore
import com.luum.michi.app.core.session.repository.SettingsStorePendingLinkStore
import com.luum.michi.app.settings.FakeSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkedAccountStorageTest {

    @Test
    fun pendingLinkSurvivesARestartUntilItIsConsumed() {
        val store = FakeSettingsStore()
        val pending = SettingsStorePendingLinkStore(store)
        assertNull(pending.load())

        pending.save(PendingLink(ProviderId.Mal, 1_000L))
        assertEquals(PendingLink(ProviderId.Mal, 1_000L), pending.load())

        val restarted = SettingsStorePendingLinkStore(store)
        assertEquals(PendingLink(ProviderId.Mal, 1_000L), restarted.load())
        restarted.clear()
        assertNull(pending.load())
    }

    @Test
    fun pendingLinkExpiresAfterTenMinutes() {
        val pending = PendingLink(ProviderId.Mal, 1_000L)
        assertTrue(pending.isValid(1_600L))
        assertFalse(pending.isValid(1_601L))
    }

    @Test
    fun linkedAccountStoreRoundTripsViewerNoticeAndAutoCopy() {
        val store = FakeSettingsStore()
        val linked = SettingsStoreLinkedAccountStore(store)
        val account = LinkedAccount(
            provider = ProviderId.AniList,
            viewer = LinkedViewer(
                id = 7,
                name = "user",
                avatarUrl = "https://example.com/a.png",
                scoreFormat = ScoreFormat.POINT_10_DECIMAL,
            ),
        )

        linked.save(account)
        linked.saveLostAccess(ProviderId.Mal)
        linked.saveAutoCopy(true)

        val restarted = SettingsStoreLinkedAccountStore(store)
        assertEquals(account, restarted.load())
        assertEquals(ProviderId.Mal, restarted.loadLostAccess())
        assertTrue(restarted.loadAutoCopy())

        restarted.clear()
        restarted.clearLostAccess()
        restarted.saveAutoCopy(false)
        assertNull(restarted.load())
        assertNull(restarted.loadLostAccess())
        assertFalse(restarted.loadAutoCopy())
    }
}
