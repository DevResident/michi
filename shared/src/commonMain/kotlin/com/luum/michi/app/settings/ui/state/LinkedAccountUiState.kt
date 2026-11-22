package com.luum.michi.app.settings.ui.state

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.session.domain.LinkedAccount

/**
 * Everything the account card needs from the linked-account manager. Built by
 * App from the session + provider list, so Settings stays free of DI wiring.
 */
internal data class LinkedAccountUiState(
    val account: LinkedAccount?,
    val linkableProviders: List<ProviderId>,
    val labels: Map<ProviderId, String>,
    val lostAccessProvider: ProviderId?,
    val error: NetworkError?,
) {
    val isEmpty: Boolean
        get() = account == null && lostAccessProvider == null && linkableProviders.isEmpty() && error == null

    fun label(provider: ProviderId): String = labels[provider] ?: provider.value
}
