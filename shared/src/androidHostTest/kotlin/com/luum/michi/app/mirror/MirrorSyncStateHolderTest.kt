package com.luum.michi.app.mirror

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
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource
import com.luum.michi.app.mirror.ui.state.MirrorSyncStateHolder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CountingSyncSource(
    private val diff: MirrorDiff,
) : MirrorSyncSource {
    var loads = 0
        private set

    override suspend fun loadPreview(): NetworkResult<MirrorDiff> {
        loads += 1
        return NetworkResult.Success(diff)
    }

    override suspend fun apply(rows: List<MirrorPreviewRow>): NetworkResult<Unit> =
        NetworkResult.Success(Unit)
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

class MirrorSyncStateHolderTest {

    @Test
    fun adoptingADiffKeepsOnlySafeRowsChecked() {
        val holder = MirrorSyncStateHolder(
            CountingSyncSource(
                diff(
                    row("safe"),
                    row("newer", newer = true),
                    row("regression", regression = true),
                ),
            ),
        )

        holder.adopt(diff(row("safe"), row("newer", newer = true)))

        assertEquals(setOf("safe"), holder.selectedKeys)
        assertNotNull(holder.preview)
    }

    @Test
    fun loadIfStaleReusesAnAdoptedPreviewWithoutAnotherRequest(): Unit = runBlocking {
        val source = CountingSyncSource(diff(row("safe")))
        val holder = MirrorSyncStateHolder(source)
        holder.adopt(diff(row("safe")))

        holder.loadIfStale()

        assertEquals(0, source.loads)
        assertNotNull(holder.preview)
    }

    @Test
    fun invalidatingThePreviewForcesAFreshLoad(): Unit = runBlocking {
        val source = CountingSyncSource(diff(row("safe")))
        val holder = MirrorSyncStateHolder(source)
        holder.adopt(diff(row("newer", newer = true)))

        holder.invalidatePreview()
        assertNull(holder.preview)
        assertTrue(holder.selectedKeys.isEmpty())

        holder.loadIfStale()

        assertEquals(1, source.loads)
        assertNotNull(holder.preview)
        assertEquals(setOf("safe"), holder.selectedKeys)
    }

    @Test
    fun anEmptyHolderLoadsAndChecksOnlySafeRows(): Unit = runBlocking {
        val source = CountingSyncSource(
            diff(
                row("safe"),
                row("newer", newer = true),
                row("regression", regression = true),
            ),
        )
        val holder = MirrorSyncStateHolder(source)

        holder.loadIfStale()

        assertEquals(1, source.loads)
        assertEquals(setOf("safe"), holder.selectedKeys)
    }

    @Test
    fun applyingKeepsThePreviewAndDoesNotReadAgain(): Unit = runBlocking {
        val source = CountingSyncSource(diff(row("safe")))
        val holder = MirrorSyncStateHolder(source)
        holder.load()
        assertEquals(1, source.loads)

        holder.apply()

        assertEquals(1, source.loads, "apply must not trigger another comparison")
        assertNotNull(holder.preview)
        assertFalse(holder.loading)
    }
}
