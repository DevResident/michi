package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.media.domain.ProviderId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Ephemeral account notices. UI maps them to text; core never imports UI. */
internal sealed interface AccountEvent {
    val provider: ProviderId

    data class Connected(override val provider: ProviderId) : AccountEvent
    data class Disconnected(override val provider: ProviderId) : AccountEvent
    data class LostAccess(override val provider: ProviderId) : AccountEvent
    data class FollowedChanged(override val provider: ProviderId) : AccountEvent
}

/**
 * Fire-and-forget account events. No replay: an event emitted while no screen
 * is collecting is simply not shown (the persistent lost-access notice and the
 * account pages carry the durable state).
 */
internal class AccountEvents {
    private val _events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AccountEvent> = _events.asSharedFlow()

    fun emit(event: AccountEvent) {
        _events.tryEmit(event)
    }
}
