package com.luum.michi.app.mirror.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.medialist.domain.MirrorLinkedOnlyRow
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.label
import com.luum.michi.app.core.medialist.repository.MirrorOutboxState
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mirror.ui.model.summaryLine
import com.luum.michi.app.mirror.ui.state.MirrorSyncStateHolder
import com.luum.michi.app.ui.components.AppButton
import com.luum.michi.app.ui.components.ButtonEmphasis
import com.luum.michi.app.ui.components.CoverSize
import com.luum.michi.app.ui.components.MediaCoverCard
import com.luum.michi.app.ui.components.MessagePanel
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.SectionHeader
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.theme.LocalWarning
import kotlinx.coroutines.launch

/**
 * Full-screen preview inside Settings. Root owns the toolbar; applying enqueues
 * the selected rows and closes the page, while the batch keeps draining in the
 * app scope.
 */
@Composable
internal fun MirrorSyncScreen(
    holder: MirrorSyncStateHolder,
    bottomPadding: Dp,
    outbox: MirrorOutboxState = MirrorOutboxState(),
    onRetry: () -> Unit = {},
    onApplied: () -> Unit,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    // Reuse the comparison the Accounts dialog already produced; a manual
    // "Compare lists" invalidates it first, so this still loads a fresh one.
    LaunchedEffect(Unit) { holder.loadIfStale() }
    val scope = rememberCoroutineScope()
    val preview = holder.preview

    when {
        holder.loading || (preview == null && holder.error == null) -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        preview == null -> MessagePanel(
            title = strings.errorUnknownLabel,
            message = holder.error?.let { strings.networkErrorMessage(it) },
            icon = AppIcons.Warning,
            actionLabel = strings.retryAction,
            onAction = { scope.launch { holder.load() } },
            modifier = modifier,
        )
        else -> {
            val hasRows = preview.additions.isNotEmpty() || preview.updates.isNotEmpty() ||
                preview.linkedOnly.isNotEmpty()
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "summary") {
                    OptionGroup(title = null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = strings.mirrorSyncAddCount(preview.additions.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = strings.mirrorSyncUpdateCount(preview.updates.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (preview.linkedOnly.isNotEmpty()) {
                                Text(
                                    text = strings.mirrorSyncLinkedOnlyCount(preview.linkedOnly.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (preview.noEquivalent > 0) {
                                Text(
                                    text = strings.mirrorSyncNoEquivalent(preview.noEquivalent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (preview.privateOmitted > 0) {
                                Text(
                                    text = strings.mirrorSyncPrivateOmitted(preview.privateOmitted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            holder.error?.let { error ->
                                Text(
                                    text = strings.networkErrorMessage(error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalWarning.current,
                                )
                            }
                        }
                    }
                }

                if (outbox.withErrors > 0) {
                    item(key = "outbox-errors") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.accountsSyncErrors(outbox.withErrors),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalWarning.current,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onRetry) {
                                Text(strings.retryAction)
                            }
                        }
                    }
                }

                if (preview.additions.isNotEmpty()) {
                    item(key = "add-title") {
                        SectionHeader(
                            title = "${strings.mirrorSyncAddSection} · ${preview.additions.size}",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(preview.additions, key = { "add-${it.key}" }) { row ->
                        MirrorPreviewRowItem(
                            row = row,
                            checked = row.key in holder.selectedKeys,
                            enabled = !holder.applying,
                            strings = strings,
                            scoreFormat = preview.targetScoreFormat,
                            onToggle = { holder.toggle(row) },
                        )
                    }
                }

                if (preview.updates.isNotEmpty()) {
                    item(key = "update-title") {
                        SectionHeader(
                            title = "${strings.mirrorSyncUpdateSection} · ${preview.updates.size}",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(preview.updates, key = { "update-${it.key}" }) { row ->
                        MirrorPreviewRowItem(
                            row = row,
                            checked = row.key in holder.selectedKeys,
                            enabled = !holder.applying,
                            strings = strings,
                            scoreFormat = preview.targetScoreFormat,
                            onToggle = { holder.toggle(row) },
                        )
                    }
                }

                if (!hasRows) {
                    item(key = "empty") {
                        MessagePanel(
                            title = strings.mirrorSyncEmptyTitle,
                            message = strings.mirrorSyncEmptyMessage,
                            icon = AppIcons.Check,
                            modifier = Modifier.height(240.dp),
                        )
                    }
                }

                if (preview.linkedOnly.isNotEmpty()) {
                    item(key = "linked-only-title") {
                        SectionHeader(
                            title = "${strings.mirrorSyncLinkedOnlySection} · ${preview.linkedOnly.size}",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(preview.linkedOnly, key = { "linked-${it.media.type}-${it.media.malId}" }) { row ->
                        LinkedOnlyRow(row, strings)
                    }
                }

                if (preview.additions.isNotEmpty() || preview.updates.isNotEmpty()) {
                    item(key = "apply") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                label = strings.mirrorSyncApply(holder.selectedCount),
                                onClick = {
                                    scope.launch {
                                        if (holder.apply() is NetworkResult.Success) onApplied()
                                    }
                                },
                                emphasis = ButtonEmphasis.Primary,
                                enabled = holder.selectedCount > 0 && !holder.applying,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = strings.mirrorSyncSlowHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorPreviewRowItem(
    row: MirrorPreviewRow,
    checked: Boolean,
    enabled: Boolean,
    strings: LanguageStrings,
    scoreFormat: ScoreFormat,
    onToggle: () -> Unit,
) {
    // The whole row toggles the check: a review page never navigates away and
    // loses the selection. The checkbox is the 48dp explicit control.
    MediaCoverCard(
        cover = row.media.cover,
        title = row.media.title,
        subtitle = row.summaryLine(strings, scoreFormat = scoreFormat),
        onClick = { if (enabled) onToggle() },
        coverSize = CoverSize.List,
        titleMaxLines = 2,
        subtitleMaxLines = 1,
        coverContentDescription = row.media.title,
        titleTrailing = {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
        },
        content = {
            if (row.newerInLinked || row.regression) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (row.newerInLinked) {
                        Text(
                            text = strings.mirrorSyncNewerInLinked,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalWarning.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (row.regression) {
                        Text(
                            text = strings.mirrorSyncRegression,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalWarning.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The row values already have the target scale; the format is only needed for
 * display. MAL is the fixed 0–10 format, while an AniList target keeps its
 * viewer format. The screen gets it from the diff's target format.
 */
@Composable
private fun LinkedOnlyRow(row: MirrorLinkedOnlyRow, strings: LanguageStrings) {
    MediaCoverCard(
        cover = row.media.cover,
        title = row.media.title,
        subtitle = row.values.status?.label(
            strings,
            isManga = row.media.type == MediaType.MANGA,
        ) ?: "—",
        // Informational: it has no check or destination.
        onClick = null,
        coverSize = CoverSize.List,
        titleMaxLines = 2,
        subtitleMaxLines = 1,
        coverContentDescription = row.media.title,
    )
}
