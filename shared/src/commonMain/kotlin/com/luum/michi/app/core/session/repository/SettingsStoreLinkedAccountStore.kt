package com.luum.michi.app.core.session.repository

import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.session.domain.LinkedAccount
import com.luum.michi.app.core.session.domain.LinkedAccountStore
import com.luum.michi.app.core.session.domain.LinkedViewer
import com.luum.michi.app.core.storage.domain.SettingsStore
import com.luum.michi.app.core.storage.domain.SettingsStoreKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LinkedAccountDto(
    val provider: String,
    val viewerId: Int,
    val name: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val scoreFormat: String? = null,
)

/** [LinkedAccountStore] over the key/value [SettingsStore] and the shared JSON. */
internal class SettingsStoreLinkedAccountStore(
    private val store: SettingsStore,
    private val json: Json = AniListJson,
) : LinkedAccountStore {

    override fun load(): LinkedAccount? {
        val raw = store.getString(SettingsStoreKeys.LinkedAccount)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val dto = runCatching { json.decodeFromString<LinkedAccountDto>(raw) }.getOrNull()
            ?: return null
        val provider = ProviderId(dto.provider)
        val format = dto.scoreFormat
            ?.let { rawFormat -> ScoreFormat.entries.firstOrNull { it.name == rawFormat } }
            ?: if (provider == ProviderId.Mal) ScoreFormat.POINT_10 else ScoreFormat.POINT_10_DECIMAL
        return LinkedAccount(
            provider = provider,
            viewer = LinkedViewer(
                id = dto.viewerId,
                name = dto.name,
                avatarUrl = dto.avatarUrl,
                bannerUrl = dto.bannerUrl,
                scoreFormat = format,
            ),
        )
    }

    override fun save(account: LinkedAccount) {
        store.putString(
            SettingsStoreKeys.LinkedAccount,
            json.encodeToString(
                LinkedAccountDto(
                    provider = account.provider.value,
                    viewerId = account.viewer.id,
                    name = account.viewer.name,
                    avatarUrl = account.viewer.avatarUrl,
                    bannerUrl = account.viewer.bannerUrl,
                    scoreFormat = account.viewer.scoreFormat.name,
                ),
            ),
        )
    }

    override fun clear() {
        store.putString(SettingsStoreKeys.LinkedAccount, "")
    }

    override fun loadLostAccess(): ProviderId? =
        store.getString(SettingsStoreKeys.LinkedLostAccess)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderId)

    override fun saveLostAccess(provider: ProviderId) {
        store.putString(SettingsStoreKeys.LinkedLostAccess, provider.value)
    }

    override fun clearLostAccess() {
        store.putString(SettingsStoreKeys.LinkedLostAccess, "")
    }

    override fun loadAutoCopy(): Boolean =
        store.getBoolean(SettingsStoreKeys.LinkedAutoCopy, false)

    override fun saveAutoCopy(enabled: Boolean) {
        store.putBoolean(SettingsStoreKeys.LinkedAutoCopy, enabled)
    }
}
