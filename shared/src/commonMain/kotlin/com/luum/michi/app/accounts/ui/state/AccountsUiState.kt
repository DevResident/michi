package com.luum.michi.app.accounts.ui.state

import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.repository.MirrorOutboxState
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.session.domain.ConnectedAccount

/** Everything the Accounts page and the Settings summary need. */
internal data class AccountsUiState(
    val connected: List<ConnectedAccount>,
    val followed: ProviderId?,
    val labels: Map<ProviderId, String>,
    val autoCopy: Boolean = false,
    val lostAccess: ProviderId? = null,
    val linkError: NetworkError? = null,
    val outbox: MirrorOutboxState = MirrorOutboxState(),
) {
    /** Configured services, in the order the auth providers expose them. */
    val providers: List<ProviderId> get() = labels.keys.toList()

    val other: ConnectedAccount?
        get() = connected.firstOrNull { it.provider != followed }

    val summary: String
        get() = providers.mapNotNull { account(it) }
            .joinToString(" · ") { label(it.provider) }

    fun label(provider: ProviderId): String = labels[provider] ?: provider.value

    fun account(provider: ProviderId): ConnectedAccount? =
        connected.firstOrNull { it.provider == provider }
}

/** An expired second-account link speaks for itself; other failures name the action. */
internal fun LanguageStrings.accountsLinkErrorMessage(error: NetworkError): String =
    if (error is NetworkError.LinkExpired) {
        errorLinkExpiredLabel
    } else {
        accountsConnectFailed + " " + networkErrorMessage(error)
    }
