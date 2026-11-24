package com.luum.michi.app.accounts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.accounts.ui.state.AccountsStateHolder
import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.accounts.ui.state.accountsLinkErrorMessage
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource
import com.luum.michi.app.mirror.ui.state.MirrorSyncStateHolder
import com.luum.michi.app.ui.components.AppButton
import com.luum.michi.app.ui.components.ButtonEmphasis
import com.luum.michi.app.ui.components.ChipRail
import com.luum.michi.app.ui.components.ChipTab
import com.luum.michi.app.ui.components.ModalSheet
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.components.ToggleRow
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.theme.LocalWarning
import com.luum.michi.app.ui.theme.ProviderBrands
import com.luum.michi.app.ui.theme.brandColor
import kotlinx.coroutines.launch

private sealed interface AccountConfirm {
    data class Disconnect(val provider: ProviderId) : AccountConfirm
    data object Logout : AccountConfirm
}

/**
 * Equal-account settings page: connect/disconnect, choose the followed list,
 * turn on the mirror and open its preview. Guests only see the connect rows.
 */
@Composable
internal fun AccountsScreen(
    state: AccountsUiState,
    coordinator: MirrorSyncSource,
    previewHolder: MirrorSyncStateHolder? = null,
    bottomPadding: Dp,
    onConnect: (ProviderId) -> Unit,
    onDisconnect: (ProviderId) -> Unit,
    onSetFollowed: (ProviderId) -> Unit,
    onSetAutoCopy: (Boolean) -> Unit,
    onDismissLostAccess: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPreview: () -> Unit,
    onLogout: () -> Unit,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(coordinator) { AccountsStateHolder(coordinator) }
    var confirm by remember { mutableStateOf<AccountConfirm?>(null) }
    var lastFollowed by remember { mutableStateOf(state.followed) }

    LaunchedEffect(state.followed, state.autoCopy, state.connected.size) {
        val previous = lastFollowed
        lastFollowed = state.followed
        if (state.connected.size != 2) {
            holder.clear()
            previewHolder?.invalidatePreview()
            return@LaunchedEffect
        }
        if (previous != null && previous != state.followed) {
            holder.clear()
            previewHolder?.invalidatePreview()
            if (state.autoCopy) {
                holder.compare()
                holder.lastDiff?.let { previewHolder?.adopt(it) }
            }
        }
    }

    // A save (or an explicit batch apply) changes the lists behind the preview.
    // The revision only moves when something is actually enqueued, so a fresh
    // dialog comparison is not thrown away by unrelated outbox progress.
    LaunchedEffect(state.outbox.revision) {
        if (state.outbox.revision > 0L) previewHolder?.invalidatePreview()
    }

    val other = state.other
    val orderedAccounts = state.providers.mapNotNull { state.account(it) }
    val activeBatch = state.outbox.batch?.takeIf { it.active }
    // Only what the user can act on: queue errors first, then live progress,
    // then pending work. A clean queue paints no extra line at all.
    val compareStatus = when {
        state.outbox.withErrors > 0 -> strings.accountsQueueErrors(state.outbox.withErrors)
        activeBatch != null -> strings.accountsSyncCopying(activeBatch.done, activeBatch.total)
        holder.comparing -> strings.accountsSyncComparing
        state.outbox.pending > 0 -> strings.accountsSyncPending(state.outbox.pending)
        holder.error != null -> strings.networkErrorMessage(requireNotNull(holder.error))
        else -> null
    }
    val compareStatusIsWarning = state.outbox.withErrors > 0 || holder.error != null
    val syncDirection = if (state.followed != null && other != null) {
        strings.accountsSyncDirection(
            state.label(state.followed),
            state.label(other.provider),
        )
    } else {
        null
    }

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
        item(key = "connected") {
            OptionGroup(title = strings.accountsConnectedSection) {
                state.providers.forEachIndexed { index, provider ->
                    val account = state.account(provider)
                    ConnectedAccountRow(
                        label = state.label(provider),
                        status = account?.let { strings.accountsConnectedAs(it.name) }
                            ?: strings.accountsNotConnected,
                        provider = provider,
                        actionLabel = if (account == null) {
                            strings.accountsConnect
                        } else {
                            strings.accountsDisconnect
                        },
                        destructive = account != null,
                        onAction = {
                            if (account == null) {
                                onConnect(provider)
                            } else {
                                confirm = AccountConfirm.Disconnect(provider)
                            }
                        },
                        divider = index > 0,
                    )
                }
                state.linkError?.let { error ->
                    Text(
                        text = strings.accountsLinkErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalWarning.current,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }

        state.lostAccess?.let { provider ->
            item(key = "lost-access") {
                OptionGroup(title = null) {
                    Text(
                        text = strings.accountsLostAccess(state.label(provider)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalWarning.current,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    if (state.labels.containsKey(provider)) {
                        SettingsRow(
                            label = strings.accountsLostAccessReconnect,
                            value = "",
                            onClick = { onConnect(provider) },
                            divider = false,
                        )
                    }
                    SettingsRow(
                        label = strings.accountsLostAccessDismiss,
                        value = "",
                        onClick = onDismissLostAccess,
                        divider = state.labels.containsKey(provider),
                    )
                }
            }
        }

        if (state.connected.size == 2 && other != null) {
            item(key = "sync-group") {
                OptionGroup(title = strings.accountsSyncSection) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = strings.accountsFollowedSection,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        ChipRail(
                            tabs = orderedAccounts.map { account ->
                                val label = state.label(account.provider)
                                ChipTab(
                                    value = account.provider,
                                    label = label,
                                    accessibilityLabel = strings.accountsFollowedChip(label),
                                )
                            },
                            selected = state.followed,
                            onSelect = onSetFollowed,
                            contentPadding = PaddingValues(0.dp),
                            fill = true,
                            modifier = Modifier.selectableGroup(),
                        )
                    }
                    ToggleRow(
                        title = strings.accountsSyncSection,
                        checked = state.autoCopy,
                        subtitle = syncDirection,
                        divider = true,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onSetAutoCopy(true)
                                previewHolder?.invalidatePreview()
                                scope.launch {
                                    holder.compare()
                                    holder.lastDiff?.let { previewHolder?.adopt(it) }
                                }
                            } else {
                                onSetAutoCopy(false)
                                holder.clear()
                                previewHolder?.invalidatePreview()
                            }
                        },
                    )
                    SettingsRow(
                        label = strings.accountsCompareLists,
                        value = "",
                        // The manual entry always opens the preview directly; the
                        // page loads its own comparison and the dialog is reserved
                        // for the automatic triggers.
                        onClick = if (holder.comparing) {
                            null
                        } else {
                            {
                                previewHolder?.invalidatePreview()
                                onOpenPreview()
                            }
                        },
                        supportingText = compareStatus,
                        supportingColor = if (compareStatusIsWarning) {
                            LocalWarning.current
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        orderedAccounts.forEach { account ->
            item(key = "account-${account.provider.value}") {
                OptionGroup(title = strings.accountsProviderSection(state.label(account.provider))) {
                    profileUrl(account.provider)?.let { editUrl ->
                        SettingsRow(
                            label = strings.accountsEditProfile,
                            value = "",
                            onClick = { onOpenUrl(editUrl) },
                            divider = false,
                        )
                    }
                    accountUrl(account.provider)?.let { manageUrl ->
                        SettingsRow(
                            label = strings.accountsManageAccount,
                            value = "",
                            onClick = { onOpenUrl(manageUrl) },
                        )
                    }
                }
            }
        }

        if (state.connected.isNotEmpty()) {
            item(key = "logout") {
                OptionGroup(title = null) {
                    SettingsRow(
                        label = strings.settingsSignOutTitle,
                        value = "",
                        onClick = { confirm = AccountConfirm.Logout },
                        divider = false,
                        valueColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    holder.dialog?.let { diff ->
        val targetLabel = state.label(diff.targetProvider)
        val additions = diff.additions.size
        val updates = diff.catchUpRows.count { it.before != null }
        val skippedNewer = diff.newerInLinkedCount
        val skippedRegression = diff.regressionCount
        AlertDialog(
            onDismissRequest = { holder.dismissDialog() },
            title = { Text(strings.accountsCompareTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.accountsCompareMessage(targetLabel, additions, updates))
                    if (skippedNewer > 0) {
                        Text(
                            text = strings.accountsCompareSkippedNewer(skippedNewer, targetLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (skippedRegression > 0) {
                        Text(
                            text = strings.accountsCompareSkippedRegression(skippedRegression),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        holder.viewChanges()
                        onOpenPreview()
                    },
                ) {
                    Text(strings.accountsCompareView)
                }
            },
            dismissButton = {
                TextButton(onClick = { holder.dismissDialog() }) {
                    Text(strings.accountsCompareLater)
                }
            },
        )
    }

    when (val current = confirm) {
        is AccountConfirm.Disconnect -> {
            val providerLabel = state.label(current.provider)
            val message = if (current.provider == state.followed && other != null) {
                strings.accountsDisconnectFollowedMessage(
                    providerLabel,
                    state.label(other.provider),
                )
            } else {
                strings.accountsDisconnectMessage(providerLabel)
            }
            ModalSheet(
                title = strings.accountsDisconnectTitle,
                dismissLabel = strings.dismissAction,
                onDismiss = { confirm = null },
                footer = {
                    AppButton(
                        label = strings.accountsDisconnect,
                        onClick = {
                            confirm = null
                            onDisconnect(current.provider)
                        },
                        emphasis = ButtonEmphasis.Secondary,
                        containerColor = null,
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AccountConfirm.Logout -> {
            ModalSheet(
                title = strings.settingsSignOutTitle,
                dismissLabel = strings.dismissAction,
                onDismiss = { confirm = null },
                footer = {
                    AppButton(
                        label = strings.settingsSignOutConfirmAction,
                        onClick = {
                            confirm = null
                            onLogout()
                        },
                        emphasis = ButtonEmphasis.Secondary,
                        containerColor = null,
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                Text(
                    text = strings.settingsSignOutConfirmMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun ConnectedAccountRow(
    label: String,
    status: String,
    provider: ProviderId,
    actionLabel: String,
    destructive: Boolean,
    onAction: () -> Unit,
    divider: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(provider)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderBadge(provider: ProviderId, modifier: Modifier = Modifier) {
    val brand = ProviderBrands.hex(provider)?.let(::brandColor)
        ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(28.dp)
            .background(brand, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (provider == ProviderId.AniList) AppIcons.AniList else AppIcons.Mal,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun profileUrl(provider: ProviderId): String? = when (provider) {
    ProviderId.AniList -> "https://anilist.co/settings/"
    ProviderId.Mal -> "https://myanimelist.net/editprofile.php"
    else -> null
}

// Only AniList links a separate account-settings page; other providers get no row.
private fun accountUrl(provider: ProviderId): String? = when (provider) {
    ProviderId.AniList -> "https://anilist.co/settings/account"
    ProviderId.Mal -> "https://myanimelist.net/editprofile.php?go=myoptions"
    else -> null
}
