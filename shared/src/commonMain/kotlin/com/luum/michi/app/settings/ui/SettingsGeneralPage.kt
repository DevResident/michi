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
import com.luum.michi.app.settings.domain.model.label
import com.luum.michi.app.settings.ui.model.StartDestinationChoice
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.components.ToggleRow
import com.luum.michi.app.ui.language.Strings

@Composable
internal fun SettingsGeneralPage(
    settingsState: SettingsState,
    startDestination: StartDestinationChoice,
    bottomPadding: Dp,
    onOpenSheet: (SettingsSheet) -> Unit,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    val authenticated = settingsState.canSync || settingsState.useLocalAdultContent
    SettingsLazyColumn(bottomPadding = bottomPadding, modifier = modifier) {
        if (settingsState.canSync) {
            settingsState.error?.let { error ->
                item(key = "error") {
                    SettingsErrorRow(
                        message = strings.networkErrorMessage(error),
                        retryLabel = strings.retryAction,
                        onRetry = { settingsState.refresh(force = true) },
                    )
                }
            }
        }
        item(key = "language") {
            OptionGroup(title = strings.languageLabel) {
                SettingsRow(
                    label = strings.settingsAppLanguageTitle,
                    value = settingsState.language.displayName,
                    onClick = { onOpenSheet(SettingsSheet.Language) },
                    divider = false,
                )
            }
        }
        item(key = "start") {
            OptionGroup(title = strings.settingsOpenAppSection) {
                SettingsRow(
                    label = strings.settingsStartTabTitle,
                    value = startDestination.tab.label(strings),
                    onClick = { onOpenSheet(SettingsSheet.StartTab) },
                    divider = false,
                )
                startDestination.sectionTitle?.let { sectionTitle ->
                    SettingsRow(
                        label = sectionTitle,
                        value = startDestination.sectionChoices
                            .firstOrNull { it.value == startDestination.sectionValue }
                            ?.label
                            .orEmpty(),
                        onClick = { onOpenSheet(SettingsSheet.StartSection) },
                    )
                }
            }
        }
        if (authenticated) {
            item(key = "content") {
                OptionGroup(title = strings.settingsContentSection) {
                    SettingsRow(
                        label = strings.settingsTitleLanguageTitle,
                        value = settingsState.titleLanguage.label(strings),
                        onClick = { onOpenSheet(SettingsSheet.TitleLanguage) },
                        divider = false,
                    )
                    SettingsRow(
                        label = strings.settingsStaffNameTitle,
                        value = settingsState.staffNameLanguage.label(strings),
                        onClick = { onOpenSheet(SettingsSheet.StaffNameLanguage) },
                    )
                    ToggleRow(
                        title = strings.settingsAdultContentTitle,
                        subtitle = strings.settingsAdultContentSubtitle,
                        checked = settingsState.displayAdultContent,
                        onCheckedChange = { settingsState.displayAdultContent = it },
                    )
                    Text(
                        text = strings.settingsContentSyncNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}
