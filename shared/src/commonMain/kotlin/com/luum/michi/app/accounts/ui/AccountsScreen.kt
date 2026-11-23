package com.luum.michi.app.accounts.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.accounts.ui.state.AccountsStateHolder
import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.accounts.ui.state.accountsLinkErrorMessage
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.media.domain.ProviderId
import com.luum.michi.app.mediaList.repository.mirror.MirrorSyncSource
import com.luum.michi.app.ui.components.AppButton
import com.luum.michi.app.ui.components.ButtonEmphasis
import com.luum.michi.app.ui.components.ModalSheet
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.PillShape
import com.luum.michi.app.ui.components.SettingsRow
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
            return@LaunchedEffect
        }
        if (previous != null && previous != state.followed) {
            holder.clear()
            if (state.autoCopy) holder.compare()
        }
    }

    val other = state.other
    val orderedAccounts = state.providers.mapNotNull { state.account(it) }
    val skippedParts = buildList {
        holder.skipped?.let { skipped ->
            if (skipped.newer > 0 && other != null) {
                add(strings.accountsSyncNewer(skipped.newer, state.label(other.provider)))
            }
            if (skipped.regression > 0) {
                add(strings.accountsSyncRegression(skipped.regression))
            }
        }
    }
    val activeBatch = state.outbox.batch?.takeIf { it.active }
    val statusText = when {
        holder.comparing -> strings.accountsSyncComparing
        holder.error != null -> strings.networkErrorMessage(requireNotNull(holder.error))
        activeBatch != null -> buildString {
            append(strings.accountsSyncCopying(activeBatch.done, activeBatch.total))
            if (activeBatch.errors > 0) {
                append(" · ")
                append(strings.accountsSyncErrors(activeBatch.errors))
            }
        }
        state.outbox.withErrors > 0 -> strings.accountsSyncErrors(state.outbox.withErrors)
        skippedParts.isNotEmpty() -> strings.accountsSyncUpToDate + " · " + skippedParts.joinToString(" · ")
        state.outbox.pending > 0 -> strings.accountsSyncPending(state.outbox.pending)
        else -> strings.accountsSyncUpToDate
    }
    val statusClick: (() -> Unit)? = when {
        holder.error != null -> ({ scope.launch { holder.compare() } })
        state.outbox.withErrors > 0 || skippedParts.isNotEmpty() -> onOpenPreview
        else -> null
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
            item(key = "followed") {
                OptionGroup(title = strings.accountsFollowedSection) {
                    ProviderSegmentedControl(
                        accounts = orderedAccounts,
                        followed = state.followed,
                        label = { state.label(it) },
                        onSelect = onSetFollowed,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text(
                        text = strings.accountsFollowedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            item(key = "sync") {
                OptionGroup(title = null) {
                    SyncStatusRow(
                        title = strings.accountsSyncSection,
                        checked = state.autoCopy,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onSetAutoCopy(true)
                                scope.launch { holder.compare() }
                            } else {
                                onSetAutoCopy(false)
                                holder.clear()
                            }
                        },
                        status = statusText,
                        statusClick = statusClick,
                        statusIsWarning = holder.error != null || state.outbox.withErrors > 0,
                        divider = false,
                    )
                    SettingsRow(
                        label = strings.accountsCompareLists,
                        value = "",
                        onClick = if (holder.comparing) {
                            null
                        } else {
                            { scope.launch { holder.compare() } }
                        },
                    )
                }
            }
        }

        orderedAccounts.forEach { account ->
            item(key = "account-${account.provider.value}") {
                OptionGroup(title = strings.accountsProviderSection(state.label(account.provider))) {
                    SettingsRow(
                        label = strings.accountsEditProfile,
                        value = "",
                        onClick = { onOpenUrl(profileUrl(account.provider)) },
                        divider = false,
                    )
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
                    holder.error?.let { error ->
                        Text(
                            text = strings.networkErrorMessage(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalWarning.current,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { holder.applyCatchUp() } },
                    enabled = !holder.applying,
                ) {
                    Text(strings.accountsCompareApply)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { holder.dismissDialog() }) {
                        Text(strings.accountsCompareLater)
                    }
                    TextButton(
                        onClick = {
                            holder.dismissDialog()
                            onOpenPreview()
                        },
                    ) {
                        Text(strings.accountsCompareView)
                    }
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
private fun ProviderSegmentedControl(
    accounts: List<com.luum.michi.app.core.session.domain.ConnectedAccount>,
    followed: ProviderId?,
    label: (ProviderId) -> String,
    onSelect: (ProviderId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(PillShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), PillShape),
    ) {
        accounts.forEachIndexed { index, account ->
            val selected = followed == account.provider
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    )
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(account.provider) },
                        role = Role.Tab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = label(account.provider),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            if (index < accounts.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
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

@Composable
private fun SyncStatusRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: String,
    statusClick: (() -> Unit)?,
    statusIsWarning: Boolean,
    divider: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = if (statusIsWarning) {
                LocalWarning.current
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (statusClick != null) {
                        Modifier.clickable(onClick = statusClick)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 12.dp),
        )
    }
}

private fun profileUrl(provider: ProviderId): String =
    if (provider == ProviderId.AniList) {
        "https://anilist.co/settings/profile"
    } else {
        "https://myanimelist.net/editprofile.php"
    }

private fun accountUrl(provider: ProviderId): String? =
    if (provider == ProviderId.AniList) {
        "https://anilist.co/settings/account"
    } else {
        null
    }
