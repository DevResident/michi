package com.luum.michi.app.mediaDetail.repository.mal

import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.mal.repository.MalEntryClient
import com.luum.michi.app.core.mal.repository.toViewerEntry
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.model.Page
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import com.luum.michi.app.mediaDetail.domain.media.MediaDetailRepository
import com.luum.michi.app.mediaDetail.domain.media.model.MediaCharacterEntry
import com.luum.michi.app.mediaDetail.domain.media.model.MediaDetail
import com.luum.michi.app.mediaDetail.domain.media.model.MediaDetailType
import com.luum.michi.app.mediaDetail.domain.media.model.MediaEntryEditorData
import com.luum.michi.app.mediaDetail.domain.media.model.MediaRecommendationEntry
import com.luum.michi.app.mediaDetail.domain.media.model.MediaStaffEntry
import com.luum.michi.app.mediaDetail.domain.media.model.toMediaType

/**
 * Decorates the AniList detail repository with the active account's MAL entry.
 * With AniList it is a transparent delegate; with MAL it keeps AniList as the
 * catalog but replaces `viewerEntry` with the MAL status.
 *
 * A MAL entry failure never hides the AniList detail: the detail simply paints
 * without an entry. The editor, which needs the entry, propagates the error
 * (`EntryNotOnProvider` when the title has no MAL record). A 401 closes the MAL
 * session through [MalEntryClient].
 */
internal class ProviderAwareMediaDetailRepository(
    private val activeProvider: () -> ProviderId,
    private val aniList: MediaDetailRepository,
    private val malEntries: MalEntryClient,
) : MediaDetailRepository {

    override suspend fun loadDetail(
        mediaId: Int,
        strings: LanguageStrings,
    ): NetworkResult<MediaDetail> {
        val result = aniList.loadDetail(mediaId, strings)
        if (activeProvider() != ProviderId.Mal) return result
        val detail = (result as? NetworkResult.Success)?.value ?: return result
        val type = detail.type.toMediaType() ?: return result
        val idMal = detail.idMal ?: return result.map { it.copy(viewerEntry = null) }

        return when (val entry = malEntries.read(mediaId, type, idMal)) {
            is NetworkResult.Success -> result.map {
                it.copy(viewerEntry = entry.value?.toViewerEntry(mediaId, type == MediaType.MANGA))
            }
            // A MAL read error must not hide the AniList detail.
            is NetworkResult.Failure -> result.map { it.copy(viewerEntry = null) }
        }
    }

    override suspend fun loadEditorData(mediaId: Int): NetworkResult<MediaEntryEditorData> {
        val result = aniList.loadEditorData(mediaId)
        if (activeProvider() != ProviderId.Mal) return result
        val data = (result as? NetworkResult.Success)?.value ?: return result
        val type = data.type.toMediaType() ?: return entryNotOnProvider()
        val idMal = data.idMal ?: return entryNotOnProvider()

        return when (val entry = malEntries.read(mediaId, type, idMal)) {
            is NetworkResult.Success -> NetworkResult.Success(
                data.copy(
                    viewerEntry = entry.value?.toViewerEntry(
                        mediaId,
                        type == MediaType.MANGA,
                    ),
                ),
            )
            is NetworkResult.Failure -> when (entry.error) {
                NetworkError.EntryNotOnProvider,
                is NetworkError.NotFound -> entryNotOnProvider()
                else -> entry
            }
        }
    }

    override suspend fun loadCharactersPage(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaCharacterEntry>> = aniList.loadCharactersPage(mediaId, page)

    override suspend fun loadStaffPage(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaStaffEntry>> = aniList.loadStaffPage(mediaId, page)

    override suspend fun loadRecommendations(
        mediaId: Int,
        page: Int,
    ): NetworkResult<Page<MediaRecommendationEntry>> = aniList.loadRecommendations(mediaId, page)

    private fun entryNotOnProvider(): NetworkResult<MediaEntryEditorData> =
        NetworkResult.Failure(NetworkError.EntryNotOnProvider)
}
