package com.luum.michi.app.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.language.domain.networkErrorMessage
import com.luum.michi.app.core.medialist.domain.label as scoreFormatLabel
import com.luum.michi.app.settings.domain.model.label as listSortLabel
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.components.ToggleRow
import com.luum.michi.app.ui.language.Strings

@Composable
internal fun SettingsMyListsPage(
    settingsState: SettingsState,
    bottomPadding: Dp,
    onOpenSheet: (SettingsSheet) -> Unit,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
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
        item(key = "order-score") {
            OptionGroup(title = strings.settingsOrderScoreSection) {
                SettingsRow(
                    label = strings.settingsListSortTitle,
                    value = settingsState.listSort.listSortLabel(strings),
                    onClick = { onOpenSheet(SettingsSheet.Sort) },
                    divider = false,
                )
                if (settingsState.canSync) {
                    SettingsRow(
                        label = strings.settingsScoreFormatTitle,
                        value = settingsState.scoreFormat.scoreFormatLabel(strings),
                        onClick = { onOpenSheet(SettingsSheet.ScoreFormat) },
                    )
                    ToggleRow(
                        title = strings.settingsAdvancedScoringTitle,
                        subtitle = strings.settingsAdvancedScoringSubtitle,
                        checked = settingsState.advancedScoring,
                        onCheckedChange = { settingsState.advancedScoring = it },
                    )
                }
            }
        }
        item(key = "sections") {
            OptionGroup(title = strings.settingsSectionsSection) {
                ToggleRow(
                    title = strings.settingsSplitCompletedAnimeTitle,
                    subtitle = strings.settingsSplitCompletedAnimeSubtitle,
                    checked = settingsState.splitCompletedAnime,
                    onCheckedChange = { settingsState.splitCompletedAnime = it },
                    divider = false,
                )
                ToggleRow(
                    title = strings.settingsSplitCompletedMangaTitle,
                    subtitle = strings.settingsSplitCompletedMangaSubtitle,
                    checked = settingsState.splitCompletedManga,
                    onCheckedChange = { settingsState.splitCompletedManga = it },
                )
                ToggleRow(
                    title = strings.settingsPersistSortTitle,
                    subtitle = strings.settingsPersistSortSubtitle,
                    checked = settingsState.persistListSort,
                    onCheckedChange = { settingsState.persistListSort = it },
                )
            }
        }
    }
}
