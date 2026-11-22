package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorDiffer
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.core.medialist.repository.MirrorBatchItem

/**
 * "Sync now" orchestration: fresh primary + linked snapshots, pure diff, then
 * an explicit batch into the same outbox used by automatic copies.
 */
internal class MirrorSyncCoordinator(
    private val snapshots: MirrorSnapshotReader,
    private val linkedAccounts: LinkedAccountManager,
    private val mirror: MirrorController,
) {
    suspend fun loadPreview(): NetworkResult<MirrorDiff> {
        val linked = linkedAccounts.linked.value
            ?: return NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("No linked account")),
            )
        val primary = when (val result = snapshots.readPrimary()) {
            is NetworkResult.Failure -> return result
            is NetworkResult.Success -> result.value
        }
        val target = when (val result = snapshots.readLinked()) {
            is NetworkResult.Failure -> return result
            is NetworkResult.Success -> result.value
        }
        val targetFormat = when (linked.provider) {
            ProviderId.Mal -> ScoreFormat.POINT_10
            else -> linked.viewer.scoreFormat
        }
        return NetworkResult.Success(
            MirrorDiffer.diff(
                primary = primary,
                linked = target,
                targetProvider = linked.provider,
                targetScoreFormat = targetFormat,
            ),
        )
    }

    suspend fun apply(rows: List<MirrorPreviewRow>): NetworkResult<Unit> {
        if (rows.isEmpty()) return NetworkResult.Success(Unit)
        val started = mirror.startBatch(
            rows.map { row -> MirrorBatchItem(row.primary, row.changedFields) },
        )
        return if (started) {
            NetworkResult.Success(Unit)
        } else {
            NetworkResult.Failure(
                NetworkError.Unknown(IllegalStateException("The mirror batch could not start")),
            )
        }
    }
}
