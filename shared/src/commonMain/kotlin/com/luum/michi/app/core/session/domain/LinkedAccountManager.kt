package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.domain.NetworkError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connected account that is not followed, plus the mirror preferences.
 * Tokens and viewer refreshes belong to [SessionManager]; this class only owns
 * the persisted identity/preferences and the UI-facing state.
 */
internal class LinkedAccountManager(
    private val store: LinkedAccountStore,
) {
    private val _linked = MutableStateFlow<LinkedAccount?>(null)
    val linked: StateFlow<LinkedAccount?> = _linked.asStateFlow()

    private val _lostAccess = MutableStateFlow<ProviderId?>(null)
    val lostAccess: StateFlow<ProviderId?> = _lostAccess.asStateFlow()

    private val _linkError = MutableStateFlow<NetworkError?>(null)
    val linkError: StateFlow<NetworkError?> = _linkError.asStateFlow()

    private val _autoCopy = MutableStateFlow(false)
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    /** Loads the persisted identity and mirror preferences; no network. */
    fun bootstrap() {
        _linked.value = store.load()
        _lostAccess.value = store.loadLostAccess()
        _autoCopy.value = store.loadAutoCopy()
    }

    fun setOther(account: LinkedAccount?) {
        if (account == null) {
            store.clear()
        } else {
            store.save(account)
        }
        _linked.value = account
    }

    fun setAutoCopy(enabled: Boolean) {
        store.saveAutoCopy(enabled)
        _autoCopy.value = enabled
    }

    fun setLostAccess(provider: ProviderId) {
        store.saveLostAccess(provider)
        _lostAccess.value = provider
    }

    fun clearLostAccess() {
        store.clearLostAccess()
        _lostAccess.value = null
    }

    fun dismissLostAccess() = clearLostAccess()

    fun onLinkFailed(error: NetworkError) {
        _linkError.value = error
    }

    fun clearLinkError() {
        _linkError.value = null
    }
}
