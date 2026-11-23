package com.luum.michi.app.core

import com.luum.michi.app.accounts.ui.state.AccountsStateHolder
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.MirrorMediaSummary
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.MirrorPreviewValues
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSyncSource(
    var preview: NetworkResult<MirrorDiff>,
) : MirrorSyncSource {
    var applied: List<MirrorPreviewRow>? = null

    override suspend fun loadPreview(): NetworkResult<MirrorDiff> = preview

    override suspend fun apply(rows: List<MirrorPreviewRow>): NetworkResult<Unit> {
        applied = rows
        return NetworkResult.Success(Unit)
    }
}

private fun values(progress: Int = 12) = MirrorPreviewValues(
    status = MediaListStatus.CURRENT,
    progress = progress,
    progressVolumes = null,
    score = 9f,
    priority = 2,
    repeat = 0,
    notes = "note",
    startedAt = null,
    completedAt = null,
)

private fun row(
    key: String,
    progress: Int = 12,
    newer: Boolean = false,
    regression: Boolean = false,
) = MirrorPreviewRow(
    key = key,
    media = MirrorMediaSummary(
        aniListId = 10,
        malId = 20,
        type = MediaType.ANIME,
        title = "Test",
    ),
    primary = MirrorEntry(
        sourceProvider = ProviderId.AniList,
        aniListId = 10,
        malId = 20,
        type = MediaType.ANIME,
        status = MediaListStatus.CURRENT,
        progress = progress,
        progressVolumes = null,
        score = 9f,
        scoreFormat = ScoreFormat.POINT_10_DECIMAL,
        repeat = 0,
        priority = 2,
        notes = "note",
        isPrivate = false,
        isRewatching = false,
        startedAt = null,
        completedAt = null,
    ),
    before = values(progress - 1),
    after = values(progress),
    changedFields = setOf(MirrorField.PROGRESS),
    newerInLinked = newer,
    regression = regression,
)

private fun diff(vararg updates: MirrorPreviewRow) = MirrorDiff(
    targetProvider = ProviderId.Mal,
    targetScoreFormat = ScoreFormat.POINT_10,
    additions = emptyList(),
    updates = updates.toList(),
    linkedOnly = emptyList(),
    noEquivalent = 0,
    privateOmitted = 0,
)

class AccountsStateHolderTest {

    @Test
    fun aSafeDifferenceOpensTheDialogAndCatchUpAppliesOnlyThoseRows() = runBlocking {
        val safe = row("safe")
        val newer = row("newer", newer = true)
        val regression = row("regression", regression = true)
        val source = FakeSyncSource(NetworkResult.Success(diff(safe, newer, regression)))
        val holder = AccountsStateHolder(source)

        holder.compare()

        assertNotNull(holder.dialog)
        assertEquals(1, holder.catchUpCount)
        assertNull(holder.skipped)

        holder.applyCatchUp()

        assertEquals(listOf("safe"), source.applied?.map { it.key })
        assertNull(holder.dialog)
    }

    @Test
    fun applyingFromTheDialogInvalidatesTheAdoptedDiff() = runBlocking {
        val source = FakeSyncSource(NetworkResult.Success(diff(row("safe"))))
        val holder = AccountsStateHolder(source)

        holder.compare()
        assertNotNull(holder.lastDiff)

        var invalidated = false
        holder.applyCatchUp { invalidated = true }

        assertTrue(invalidated)
        assertNull(holder.lastDiff)
    }

    @Test
    fun noApplicableRowsSkipTheDialogAndExposeTheSkippedCounts() = runBlocking {
        val source = FakeSyncSource(
            NetworkResult.Success(
                diff(
                    row("newer", newer = true),
                    row("regression", regression = true),
                ),
            ),
        )
        val holder = AccountsStateHolder(source)

        holder.compare()

        assertNull(holder.dialog)
        assertEquals(1, holder.skipped?.newer)
        assertEquals(1, holder.skipped?.regression)
    }

    @Test
    fun aZeroDiffIsUpToDate() = runBlocking {
        val holder = AccountsStateHolder(FakeSyncSource(NetworkResult.Success(diff())))

        holder.compare()

        assertNull(holder.dialog)
        assertNull(holder.skipped)
        assertNull(holder.error)
    }

    @Test
    fun aNetworkErrorKeepsTheToggleIndependentAndExposesTheError() = runBlocking {
        val holder = AccountsStateHolder(
            FakeSyncSource(NetworkResult.Failure(NetworkError.NoConnection)),
        )

        holder.compare()

        assertEquals(NetworkError.NoConnection, holder.error)
        assertNull(holder.dialog)
        assertTrue(!holder.comparing)
    }
}
