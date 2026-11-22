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

/** The preview/apply seam so the Accounts page can be tested without network. */
internal interface MirrorSyncSource {
    suspend fun loadPreview(): NetworkResult<MirrorDiff>
    suspend fun apply(rows: List<MirrorPreviewRow>): NetworkResult<Unit>
}

/**
 * "Sync now" orchestration: fresh primary + linked snapshots, pure diff, then
 * an explicit batch into the same outbox used by automatic copies.
 */
internal class MirrorSyncCoordinator(
    private val snapshots: MirrorSnapshotReader,
    private val linkedAccounts: LinkedAccountManager,
    private val mirror: MirrorController,
) : MirrorSyncSource {
    override suspend fun loadPreview(): NetworkResult<MirrorDiff> {
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
        var diff = MirrorDiffer.diff(
            primary = primary,
            linked = target,
            targetProvider = linked.provider,
            targetScoreFormat = targetFormat,
        )
        if (linked.provider == ProviderId.Mal) {
            // MAL's list doesn't carry the work's totals. Fetch them only for
            // rows that would increase progress/volumes; unknown stays uncapped.
            val candidates = diff.updates.mapNotNull { row ->
                val before = row.before ?: return@mapNotNull null
                val progressUp = row.after.progress > before.progress
                val volumesUp = (row.after.progressVolumes ?: 0) >
                    (before.progressVolumes ?: 0)
                if (!progressUp && !volumesUp) return@mapNotNull null
                row.media.malId?.let { row.media.type to it }
            }
            val totals = snapshots.readMalTotals(candidates)
            if (totals.isNotEmpty()) {
                val enriched = target.copy(
                    entries = target.entries.map { entry ->
                        val total = totals[entry.media.type to entry.media.malId]
                        if (total == null) {
                            entry
                        } else {
                            entry.copy(
                                media = entry.media.copy(
                                    totalEpisodes = total.episodes,
                                    totalChapters = total.chapters,
                                    totalVolumes = total.volumes,
                                ),
                            )
                        }
                    },
                )
                diff = MirrorDiffer.diff(
                    primary = primary,
                    linked = enriched,
                    targetProvider = linked.provider,
                    targetScoreFormat = targetFormat,
                )
            }
        }
        return NetworkResult.Success(diff)
    }

    override suspend fun apply(rows: List<MirrorPreviewRow>): NetworkResult<Unit> {
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
