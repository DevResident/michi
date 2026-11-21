package com.luum.michi.app.core.auth.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists one provider's [AuthToken] across app launches. There is one
 * instance per provider; the platform storages key their files/keys by
 * provider so two providers never share a token.
 */
internal interface TokenStorage {
    val tokenFlow: StateFlow<AuthToken?>
    suspend fun save(token: AuthToken)
    suspend fun load(): AuthToken?
    suspend fun clear()
}

internal class InMemoryTokenStorage : TokenStorage {
    private val state = MutableStateFlow<AuthToken?>(null)
    override val tokenFlow: StateFlow<AuthToken?> = state.asStateFlow()

    override suspend fun save(token: AuthToken) {
        state.value = token
    }

    override suspend fun load(): AuthToken? = state.value

    override suspend fun clear() {
        state.value = null
    }
}
