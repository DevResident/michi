package com.luum.michi.app.mediaList.repository.mirror

import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorDiffer
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.MirrorSnapshot
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.session.domain.LinkedAccountManager
import com.luum.michi.app.core.medialist.repository.MirrorBatchItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.TimeSource

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
    private val isDebug: Boolean = false,
) : MirrorSyncSource {
    override suspend fun loadPreview(): NetworkResult<MirrorDiff> = coroutineScope {
        val started = TimeSource.Monotonic.markNow()
        var resultDiff: MirrorDiff? = null
        try {
            val linked = linkedAccounts.linked.value
                ?: return@coroutineScope NetworkResult.Failure(
                    NetworkError.Unknown(IllegalStateException("No linked account")),
                )
            // Both accounts are independent: read them together, then combine in
            // the same order as before so the diff cannot change.
            val primaryRead = async { timedRead("followed") { snapshots.readPrimary() } }
            val targetRead = async { timedRead("other") { snapshots.readLinked() } }
            val primaryResult = primaryRead.await()
            val primary = when (primaryResult) {
                is NetworkResult.Failure -> {
                    // Same failure precedence as the serial read: the followed
                    // account failing means the other read is no longer needed.
                    targetRead.cancel()
                    return@coroutineScope primaryResult
                }
                is NetworkResult.Success -> primaryResult.value
            }
            val targetResult = targetRead.await()
            val target = when (targetResult) {
                is NetworkResult.Failure -> return@coroutineScope targetResult
                is NetworkResult.Success -> targetResult.value
            }
            val targetFormat = when (linked.provider) {
                ProviderId.Mal -> ScoreFormat.POINT_10
                else -> linked.viewer.scoreFormat
            }
            val diffMark = TimeSource.Monotonic.markNow()
            var diff = MirrorDiffer.diff(
                primary = primary,
                linked = target,
                targetProvider = linked.provider,
                targetScoreFormat = targetFormat,
            )
            if (isDebug) {
                println(
                    "[Michi] compare diff ${diffMark.elapsedNow().inWholeMilliseconds}ms " +
                        diff.debugCounts(),
                )
            }
            if (linked.provider == ProviderId.Mal) {
                // MAL's list already carries the work totals for the fields the
                // app requests. Only the rows whose target snapshot is missing
                // the needed total fall back to the MAL work detail; unknown
                // stays uncapped.
                val targetMedia = target.entries.mapNotNull { entry ->
                    entry.media.malId?.let { malId ->
                        (entry.media.type to malId) to entry.media
                    }
                }.toMap()
                val candidates = diff.updates.mapNotNull { row ->
                    val before = row.before ?: return@mapNotNull null
                    val progressUp = row.after.progress > before.progress
                    val volumesUp = (row.after.progressVolumes ?: 0) >
                        (before.progressVolumes ?: 0)
                    if (!progressUp && !volumesUp) return@mapNotNull null
                    val malId = row.media.malId ?: return@mapNotNull null
                    val key = row.media.type to malId
                    val media = targetMedia[key]
                    val progressMissing = when (row.media.type) {
                        MediaType.ANIME -> media?.totalEpisodes == null
                        MediaType.MANGA -> media?.totalChapters == null
                    }
                    val volumesMissing = media?.totalVolumes == null
                    if ((!progressUp || !progressMissing) &&
                        (!volumesUp || !volumesMissing)
                    ) {
                        return@mapNotNull null
                    }
                    key
                }
                val totalsMark = TimeSource.Monotonic.markNow()
                val totals = snapshots.readMalTotals(candidates)
                if (isDebug) {
                    println(
                        "[Michi] compare malTotals " +
                            "${totalsMark.elapsedNow().inWholeMilliseconds}ms " +
                            "requests=${candidates.distinct().size} resolved=${totals.size}",
                    )
                }
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
                    val enrichedMark = TimeSource.Monotonic.markNow()
                    diff = MirrorDiffer.diff(
                        primary = primary,
                        linked = enriched,
                        targetProvider = linked.provider,
                        targetScoreFormat = targetFormat,
                    )
                    if (isDebug) {
                        println(
                            "[Michi] compare diff-after-totals " +
                                "${enrichedMark.elapsedNow().inWholeMilliseconds}ms " +
                                diff.debugCounts(),
                        )
                    }
                }
            } else if (isDebug) {
                println("[Michi] compare malTotals 0ms requests=0 resolved=0")
            }
            resultDiff = diff
            NetworkResult.Success(diff)
        } finally {
            if (isDebug) {
                println(
                    "[Michi] compare total ${started.elapsedNow().inWholeMilliseconds}ms " +
                        (resultDiff?.debugCounts() ?: "failed"),
                )
            }
        }
    }

    private suspend fun timedRead(
        label: String,
        block: suspend () -> NetworkResult<MirrorSnapshot>,
    ): NetworkResult<MirrorSnapshot> {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        if (isDebug) {
            val summary = when (result) {
                is NetworkResult.Success -> "${result.value.entries.size} rows"
                is NetworkResult.Failure -> "failed ${result.error.debugLabel()}"
            }
            println(
                "[Michi] compare $label ${mark.elapsedNow().inWholeMilliseconds}ms $summary",
            )
        }
        return result
    }

    private fun MirrorDiff.debugCounts(): String =
        "add=${additions.size} update=${updates.size} linkedOnly=${linkedOnly.size} " +
            "noEquivalent=$noEquivalent private=$privateOmitted"

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
