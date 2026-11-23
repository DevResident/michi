package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorTranslator
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.repository.MediaListEntryRepositoryImpl
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mal.MalMediaListEntryRepository

/** One linked-account destination. */
internal interface MirrorWriter {
    val provider: ProviderId
    suspend fun write(entry: MirrorEntry, fields: Set<MirrorField>): NetworkResult<Unit>
}

/** Copies into the linked MAL list through the normal MAL repository. */
internal class MalMirrorWriter(
    private val repository: MalMediaListEntryRepository,
) : MirrorWriter {
    override val provider: ProviderId = ProviderId.Mal

    override suspend fun write(
        entry: MirrorEntry,
        fields: Set<MirrorField>,
    ): NetworkResult<Unit> {
        val values = MirrorTranslator.toMal(entry)
            ?: return NetworkResult.Failure(
                NetworkError.GraphQL(listOf("Mirror entry has no status")),
            )
        return repository.saveMirror(entry, values, fields)
    }
}

/** Copies into the linked AniList list with the private/hidden/advanced fields omitted. */
internal class AniListMirrorWriter(
    private val repository: MediaListEntryRepositoryImpl,
    private val targetFormat: () -> ScoreFormat,
) : MirrorWriter {
    override val provider: ProviderId = ProviderId.AniList

    override suspend fun write(
        entry: MirrorEntry,
        fields: Set<MirrorField>,
    ): NetworkResult<Unit> {
        val values = MirrorTranslator.toAniList(entry, targetFormat())
            ?: return NetworkResult.Failure(
                NetworkError.GraphQL(listOf("Mirror entry has no status")),
            )
        return repository.saveMirror(entry, values, fields)
    }
}
