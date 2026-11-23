package com.luum.michi.app.mirror.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource

/**
 * State for the "Sync now" preview page. The page can close immediately after
 * applying: the actual copies continue in the app-scoped mirror controller.
 */
internal class MirrorSyncStateHolder(
    private val coordinator: MirrorSyncSource,
) {
    var loading by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    var preview by mutableStateOf<MirrorDiff?>(null)
        private set
    var error by mutableStateOf<NetworkError?>(null)
        private set
    var selectedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    val selectedRows: List<MirrorPreviewRow>
        get() {
            val current = preview ?: return emptyList()
            return (current.additions + current.updates).filter { it.key in selectedKeys }
        }

    val selectedCount: Int get() = selectedRows.size

    suspend fun load() {
        if (loading) return
        loading = true
        error = null
        preview = null
        selectedKeys = emptySet()
        when (val result = coordinator.loadPreview()) {
            is NetworkResult.Success -> adopt(result.value)
            is NetworkResult.Failure -> error = result.error
        }
        loading = false
    }

    /**
     * Reuses a comparison already produced by the Accounts dialog. The page
     * only loads when it has nothing fresh to paint.
     */
    suspend fun loadIfStale() {
        if (preview != null) return
        load()
    }

    /** Takes a fresh diff from the Accounts compare flow, with the same defaults. */
    fun adopt(diff: MirrorDiff) {
        error = null
        preview = diff
        selectedKeys = buildSet {
            diff.additions.forEach { add(it.key) }
            diff.updates
                .filterNot { it.newerInLinked || it.regression }
                .forEach { add(it.key) }
        }
    }

    /** Drops a comparison that can no longer be trusted (manual compare, save). */
    fun invalidatePreview() {
        preview = null
        selectedKeys = emptySet()
        error = null
    }

    fun toggle(row: MirrorPreviewRow) {
        selectedKeys = if (row.key in selectedKeys) {
            selectedKeys - row.key
        } else {
            selectedKeys + row.key
        }
    }

    suspend fun apply(): NetworkResult<Unit> {
        if (applying) {
            return NetworkResult.Failure(
                com.luum.michi.app.core.network.domain.NetworkError.Unknown(
                    IllegalStateException("A mirror batch is already applying"),
                ),
            )
        }
        val rows = selectedRows
        if (rows.isEmpty()) return NetworkResult.Success(Unit)
        applying = true
        error = null
        val result = coordinator.apply(rows)
        applying = false
        if (result is NetworkResult.Failure) error = result.error
        return result
    }
}
