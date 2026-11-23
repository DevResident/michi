package com.luum.michi.app.accounts

import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.accounts.ui.state.accountsLinkErrorMessage
import com.luum.michi.app.core.language.domain.EnglishStrings
import com.luum.michi.app.core.language.domain.SpanishStrings
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.session.domain.ConnectedAccount
import kotlin.test.Test
import kotlin.test.assertEquals

private fun account(provider: ProviderId, name: String) = ConnectedAccount(
    provider = provider,
    viewerId = if (provider == ProviderId.Mal) 2 else 1,
    name = name,
    scoreFormat = if (provider == ProviderId.Mal) {
        ScoreFormat.POINT_10
    } else {
        ScoreFormat.POINT_10_DECIMAL
    },
)

private val labels = mapOf(
    ProviderId.AniList to "AniList",
    ProviderId.Mal to "MyAnimeList",
)

class AccountsUiStateTest {

    @Test
    fun oneAccountHasNoDanglingSeparator() {
        val state = AccountsUiState(
            connected = listOf(account(ProviderId.AniList, "ani")),
            followed = ProviderId.AniList,
            labels = labels,
        )

        assertEquals("AniList", state.summary)
    }

    @Test
    fun twoAccountsUseTheMiddleDot() {
        val state = AccountsUiState(
            connected = listOf(
                account(ProviderId.AniList, "ani"),
                account(ProviderId.Mal, "mal"),
            ),
            followed = ProviderId.Mal,
            labels = labels,
        )

        assertEquals("AniList · MyAnimeList", state.summary)
    }

    @Test
    fun aGuestHasNoSummary() {
        val state = AccountsUiState(
            connected = emptyList(),
            followed = null,
            labels = labels,
        )

        assertEquals("", state.summary)
    }

    @Test
    fun anExpiredLinkShowsItsMessageWithoutTheGenericConnectPrefix() {
        assertEquals(
            "The link expired. Please try again.",
            EnglishStrings.accountsLinkErrorMessage(NetworkError.LinkExpired),
        )
        assertEquals(
            "El enlace caducó. Vuelve a intentarlo.",
            SpanishStrings.accountsLinkErrorMessage(NetworkError.LinkExpired),
        )
        assertEquals(
            EnglishStrings.accountsConnectFailed + " " + EnglishStrings.errorNoConnectionLabel,
            EnglishStrings.accountsLinkErrorMessage(NetworkError.NoConnection),
        )
    }
}
