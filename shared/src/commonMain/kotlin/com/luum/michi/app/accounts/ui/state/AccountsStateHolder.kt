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
 * The automatic compare flow of the Accounts page: it only reads and opens the
 * review dialog. Applying always happens on the Compare lists page.
 */
internal class AccountsStateHolder(
    private val coordinator: MirrorSyncSource,
) {
    var comparing by mutableStateOf(false)
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

    suspend fun compare() {
        val id = ++request
        comparing = true
        error = null
        dialog = null
        skipped = null
        lastDiff = null
        try {
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
        } finally {
            if (id == request) comparing = false
        }
    }

    /** The automatic dialog only inspects; it never writes to the other account. */
    fun viewChanges() {
        dialog = null
    }

    fun dismissDialog() {
        dialog = null
    }

    fun clear() {
        request++
        comparing = false
        dialog = null
        skipped = null
        error = null
        lastDiff = null
    }
}
