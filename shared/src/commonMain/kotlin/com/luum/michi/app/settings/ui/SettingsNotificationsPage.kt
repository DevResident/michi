package com.luum.michi.app.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.ToggleRow
import com.luum.michi.app.ui.language.Strings

@Composable
internal fun SettingsNotificationsPage(
    settingsState: SettingsState,
    bottomPadding: Dp,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    // The row is only in the index for AniList; keep the page inert if a stale
    // navigation state ever lands here on another provider.
    if (!settingsState.canSync) return

    SettingsLazyColumn(bottomPadding = bottomPadding, modifier = modifier) {
        settingsState.error?.let { error ->
            item(key = "error") {
                SettingsErrorRow(
                    message = strings.networkErrorMessage(error),
                    retryLabel = strings.retryAction,
                    onRetry = { settingsState.refresh(force = true) },
                )
            }
        }
        item(key = "notices") {
            OptionGroup(title = strings.settingsNoticesSection) {
                ToggleRow(
                    title = strings.notificationsAiringTitle,
                    subtitle = null,
                    checked = settingsState.notifications.airing,
                    onCheckedChange = {
                        settingsState.notifications = settingsState.notifications.copy(airing = it)
                    },
                    divider = false,
                )
                ToggleRow(
                    title = strings.notificationsMessagesTitle,
                    subtitle = null,
                    checked = settingsState.notifications.messages,
                    onCheckedChange = {
                        settingsState.notifications = settingsState.notifications.copy(messages = it)
                    },
                )
                ToggleRow(
                    title = strings.notificationsMediaTitle,
                    subtitle = null,
                    checked = settingsState.notifications.media,
                    onCheckedChange = {
                        settingsState.notifications = settingsState.notifications.copy(media = it)
                    },
                )
                Text(
                    text = strings.settingsNotificationsSyncNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }
    }
}
