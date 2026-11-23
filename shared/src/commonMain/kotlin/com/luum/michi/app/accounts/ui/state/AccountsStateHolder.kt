package com.luum.michi.app.accounts.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luum.michi.app.core.medialist.domain.MirrorDiff
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource

internal data class CompareSkipped(
    val newer: Int,
    val regression: Int,
)

/**
 * The compare/catch-up flow of the Accounts page. The actual copy keeps running
 * in the app-scoped mirror controller, so leaving the page never stops it.
 */
internal class AccountsStateHolder(
    private val coordinator: MirrorSyncSource,
) {
    var comparing by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    var dialog by mutableStateOf<MirrorDiff?>(null)
        private set
    var skipped by mutableStateOf<CompareSkipped?>(null)
        private set
    var error by mutableStateOf<NetworkError?>(null)
        private set

    /** Last successful comparison, shared with the preview page so it is not read twice. */
    var lastDiff by mutableStateOf<MirrorDiff?>(null)
        private set

    private var request = 0

    val catchUpCount: Int get() = dialog?.catchUpRows?.size ?: 0

    suspend fun compare() {
        val id = ++request
        comparing = true
        error = null
        dialog = null
        skipped = null
        lastDiff = null
        when (val result = coordinator.loadPreview()) {
            is NetworkResult.Success -> {
                if (id != request) return
                val diff = result.value
                lastDiff = diff
                if (diff.catchUpRows.isEmpty()) {
                    skipped = if (diff.newerInLinkedCount + diff.regressionCount > 0) {
                        CompareSkipped(
                            newer = diff.newerInLinkedCount,
                            regression = diff.regressionCount,
                        )
                    } else {
                        null
                    }
                } else {
                    dialog = diff
                }
            }
            is NetworkResult.Failure -> {
                if (id != request) return
                error = result.error
            }
        }
        if (id == request) comparing = false
    }

    suspend fun applyCatchUp(onApplied: () -> Unit = {}): NetworkResult<Unit> {
        val diff = dialog ?: return NetworkResult.Success(Unit)
        val rows = diff.catchUpRows
        if (rows.isEmpty()) {
            dialog = null
            lastDiff = null
            onApplied()
            return NetworkResult.Success(Unit)
        }
        applying = true
        error = null
        val result = coordinator.apply(rows)
        applying = false
        if (result is NetworkResult.Success) {
            dialog = null
            // The preview is now stale: applying from the dialog enqueued the
            // same rows, so the next "View changes" must read the lists again.
            lastDiff = null
            onApplied()
        } else {
            error = (result as NetworkResult.Failure).error
        }
        return result
    }

    fun dismissDialog() {
        dialog = null
    }

    fun clear() {
        request++
        comparing = false
        applying = false
        dialog = null
        skipped = null
        error = null
        lastDiff = null
    }
}
