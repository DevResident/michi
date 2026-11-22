package com.luum.michi.app.ui.theme

import com.luum.michi.app.core.media.domain.ProviderId

/**
 * The only source of provider brand colors. The same raw hex feeds the login
 * buttons (`AuthService.brandHex`) and the account badges, so those two surfaces
 * can never drift apart.
 */
internal object ProviderBrands {
    const val AniList = "#02A9FF"
    const val Mal = "#2E51A2"

    fun hex(provider: ProviderId): String? = when (provider) {
        ProviderId.AniList -> AniList
        ProviderId.Mal -> Mal
        else -> null
    }
}
