package com.luum.michi.app.core.session.domain

import com.luum.michi.app.core.media.domain.ProviderId

/**
 * Which provider currently owns the session. Persisted so a cold start knows
 * whose token to load; AniList is the default when nothing is stored.
 */
internal interface ActiveProviderStore {
    fun load(): ProviderId
    fun save(provider: ProviderId)
    fun clear()
}
