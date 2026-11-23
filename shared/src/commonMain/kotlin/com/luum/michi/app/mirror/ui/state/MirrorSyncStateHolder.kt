package com.luum.michi.app.mirror.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource
import com.luum.michi.app.mirror.ui.model.MirrorSection
import com.luum.michi.app.mirror.ui.model.MirrorTab
import com.luum.michi.app.mirror.ui.model.mirrorTabs

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
    var selectedSection by mutableStateOf<MirrorSection?>(null)
        private set

    val tabs: List<MirrorTab> get() = preview?.let(::mirrorTabs).orEmpty()

    /**
     * The tab painted now. A still-visible previous selection survives a reload;
     * if its section disappeared, the first section with rows takes over.
     */
    val activeSection: MirrorSection?
        get() {
            val visible = tabs
            return selectedSection?.takeIf { section ->
                visible.any { it.section == section }
            } ?: visible.firstOrNull()?.section
        }

    /** Rows that can be selected from the active tab (linked-only has none). */
    val selectableRows: List<MirrorPreviewRow>
        get() = when (activeSection) {
            MirrorSection.UPDATE -> updatesWithoutWarnings()
            MirrorSection.ADD -> preview?.additions.orEmpty()
            MirrorSection.LINKED_ONLY, null -> emptyList()
        }

    /** Every row painted in the active tab, safe or not. */
    val activeRows: List<MirrorPreviewRow>
        get() = when (activeSection) {
            MirrorSection.UPDATE -> preview?.updates.orEmpty()
            MirrorSection.ADD -> preview?.additions.orEmpty()
            MirrorSection.LINKED_ONLY, null -> emptyList()
        }

    val activeSelectedCount: Int
        get() = activeRows.count { it.key in selectedKeys }

    val activeRowCount: Int
        get() = activeRows.size

    val allSelectableSelected: Boolean
        get() {
            val selectable = selectableRows
            return selectable.isNotEmpty() && selectable.all { it.key in selectedKeys }
        }

    val selectedRows: List<MirrorPreviewRow>
        get() {
            val current = preview ?: return emptyList()
            return (current.additions + current.updates).filter { it.key in selectedKeys }
        }

    val selectedCount: Int get() = selectedRows.size

    fun selectSection(section: MirrorSection) {
        selectedSection = section
    }

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

    /**
     * Select all safe rows of the active tab, or clear every row in it. Update
     * never auto-selects "newer in the linked account" or regression rows.
     */
    fun toggleSelectAll() {
        if (activeSection == null || activeSection == MirrorSection.LINKED_ONLY) return
        val rows = activeRows
        if (rows.isEmpty()) return
        selectedKeys = if (allSelectableSelected) {
            selectedKeys - rows.map { it.key }.toSet()
        } else {
            selectedKeys + selectableRows.map { it.key }
        }
    }

    private fun updatesWithoutWarnings(): List<MirrorPreviewRow> =
        preview?.updates.orEmpty().filterNot { it.newerInLinked || it.regression }

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
