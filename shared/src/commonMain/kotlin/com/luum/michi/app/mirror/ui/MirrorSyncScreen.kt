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
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.core.medialist.domain.MirrorLinkedOnlyRow
import com.luum.michi.app.core.medialist.domain.MirrorPreviewRow
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.label
import com.luum.michi.app.core.medialist.repository.MirrorOutboxState
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.mirror.ui.model.MirrorSection
import com.luum.michi.app.mirror.ui.model.summaryLine
import com.luum.michi.app.mirror.ui.state.MirrorSyncStateHolder
import com.luum.michi.app.ui.components.AppButton
import com.luum.michi.app.ui.components.ButtonEmphasis
import com.luum.michi.app.ui.components.ChipRail
import com.luum.michi.app.ui.components.ChipTab
import com.luum.michi.app.ui.components.CoverSize
import com.luum.michi.app.ui.components.GlassBar
import com.luum.michi.app.ui.components.GlassBarClearance
import com.luum.michi.app.ui.components.LoadingPanel
import com.luum.michi.app.ui.components.MediaCoverCard
import com.luum.michi.app.ui.components.MessagePanel
import com.luum.michi.app.ui.components.TrailingPill
import com.luum.michi.app.ui.components.rememberResetListState
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.theme.LocalWarning
import kotlinx.coroutines.launch

/**
 * Full-screen preview inside Settings. Root owns the toolbar; applying enqueues
 * the selected rows and closes the page, while the batch keeps draining in the
 * app scope.
 *
 * The page is organised by the three comparison sections: Update, Add and Only
 * in the other account. Only the selected section is painted; the tabs carry
 * their own counts and the initial tab is the first one with rows. The previous
 * tab survives a reload while it still has rows.
 */
@Composable
internal fun MirrorSyncScreen(
    holder: MirrorSyncStateHolder,
    bottomPadding: Dp,
    outbox: MirrorOutboxState = MirrorOutboxState(),
    onRetry: () -> Unit = {},
    providerLabel: (ProviderId) -> String = { it.value },
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
        holder.loading || (preview == null && holder.error == null) -> LoadingPanel(
            label = strings.mirrorSyncLoading,
            waitingLabel = strings.networkThrottleWaitingLabel,
            modifier = modifier,
        )
        preview == null -> MessagePanel(
            title = strings.errorUnknownLabel,
            message = holder.error?.let { strings.networkErrorMessage(it) },
            icon = AppIcons.Warning,
            actionLabel = strings.retryAction,
            onAction = { scope.launch { holder.load() } },
            modifier = modifier,
        )
        else -> {
            val tabs = holder.tabs
            val active = holder.activeSection
            val hasSelectableBar = preview.additions.isNotEmpty() || preview.updates.isNotEmpty()
            val listState = rememberResetListState(
                listOf(preview.targetProvider, active),
            )
            Box(modifier = modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (tabs.isNotEmpty()) {
                        ChipRail(
                            tabs = tabs.map { tab ->
                                ChipTab(
                                    value = tab.section,
                                    label = tab.section.label(strings),
                                    count = tab.count,
                                )
                            },
                            selected = active,
                            onSelect = holder::selectSection,
                            fill = true,
                        )
                    }

                    if (active == MirrorSection.UPDATE || active == MirrorSection.ADD) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.mirrorSyncSelection(
                                    holder.activeSelectedCount,
                                    holder.activeRowCount,
                                    providerLabel(preview.targetProvider),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (holder.selectableRows.isNotEmpty()) {
                                TrailingPill(
                                    label = if (holder.allSelectableSelected) {
                                        strings.mirrorSyncClearSelection
                                    } else {
                                        strings.mirrorSyncSelectAll
                                    },
                                    onClick = holder::toggleSelectAll,
                                )
                            }
                        }
                    }

                    if (preview.noEquivalent > 0 || preview.privateOmitted > 0) {
                        Text(
                            text = buildString {
                                if (preview.noEquivalent > 0) {
                                    append(strings.mirrorSyncNoEquivalent(preview.noEquivalent))
                                }
                                if (preview.privateOmitted > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append(strings.mirrorSyncPrivateOmitted(preview.privateOmitted))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }

                    holder.error?.let { error ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 4.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.networkErrorMessage(error),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalWarning.current,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (holder.apply() is NetworkResult.Success) onApplied()
                                    }
                                },
                            ) {
                                Text(strings.retryAction)
                            }
                        }
                    }

                    if (outbox.withErrors > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 4.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.accountsSyncErrors(outbox.withErrors),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalWarning.current,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onRetry) {
                                Text(strings.retryAction)
                            }
                        }
                    }

                    if (active == null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            MessagePanel(
                                title = strings.mirrorSyncEmptyTitle,
                                message = strings.mirrorSyncEmptyMessage,
                                icon = AppIcons.Check,
                                modifier = Modifier.height(240.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 8.dp,
                                end = 16.dp,
                                bottom = 16.dp + bottomPadding +
                                    if (hasSelectableBar) GlassBarClearance else 0.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (active) {
                                MirrorSection.UPDATE -> items(
                                    items = preview.updates,
                                    key = { "update-${it.key}" },
                                ) { row ->
                                    MirrorPreviewRowItem(
                                        row = row,
                                        checked = row.key in holder.selectedKeys,
                                        enabled = !holder.applying,
                                        strings = strings,
                                        scoreFormat = preview.targetScoreFormat,
                                        onToggle = { holder.toggle(row) },
                                    )
                                }

                                MirrorSection.ADD -> items(
                                    items = preview.additions,
                                    key = { "add-${it.key}" },
                                ) { row ->
                                    MirrorPreviewRowItem(
                                        row = row,
                                        checked = row.key in holder.selectedKeys,
                                        enabled = !holder.applying,
                                        strings = strings,
                                        scoreFormat = preview.targetScoreFormat,
                                        onToggle = { holder.toggle(row) },
                                    )
                                }

                                MirrorSection.LINKED_ONLY -> items(
                                    items = preview.linkedOnly,
                                    key = { "linked-${it.media.type}-${it.media.malId}" },
                                ) { row ->
                                    LinkedOnlyRow(row, strings)
                                }
                            }
                        }
                    }
                }

                if (hasSelectableBar) {
                    GlassBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = strings.mirrorSyncBackgroundCopy,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AppButton(
                            label = if (holder.applying) {
                                strings.mirrorSyncApplying
                            } else {
                                strings.mirrorSyncApply(holder.selectedCount)
                            },
                            onClick = {
                                scope.launch {
                                    if (holder.apply() is NetworkResult.Success) onApplied()
                                }
                            },
                            emphasis = ButtonEmphasis.Primary,
                            enabled = holder.selectedCount > 0 && !holder.applying,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun MirrorSection.label(strings: LanguageStrings): String = when (this) {
    MirrorSection.UPDATE -> strings.mirrorSyncUpdateSection
    MirrorSection.ADD -> strings.mirrorSyncAddSection
    MirrorSection.LINKED_ONLY -> strings.mirrorSyncLinkedOnlySection
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
    // The card height is fixed. A warning row already spends a line on the
    // alert, so it gets two subtitle lines; safe rows can use the full three.
    val hasWarning = row.newerInLinked || row.regression
    MediaCoverCard(
        cover = row.media.cover,
        title = row.media.title,
        subtitle = row.summaryLine(strings, scoreFormat = scoreFormat),
        onClick = { if (enabled) onToggle() },
        coverSize = CoverSize.List,
        titleMaxLines = 2,
        subtitleMaxLines = if (hasWarning) 2 else 3,
        coverContentDescription = row.media.title,
        titleTrailing = {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
        },
        content = {
            if (hasWarning) {
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
