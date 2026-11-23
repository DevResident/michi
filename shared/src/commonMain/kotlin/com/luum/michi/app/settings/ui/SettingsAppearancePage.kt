package com.luum.michi.app.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.theme.AppPalette
import com.luum.michi.app.ui.theme.displayName

@Composable
internal fun SettingsAppearancePage(
    settingsState: SettingsState,
    bottomPadding: Dp,
    onOpenSheet: (SettingsSheet) -> Unit,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    SettingsLazyColumn(bottomPadding = bottomPadding, modifier = modifier) {
        item(key = "theme") {
            OptionGroup(title = strings.settingsThemeTitle) {
                SettingsRow(
                    label = strings.settingsThemeTitle,
                    value = "${settingsState.themeType.label(strings)} · " +
                        settingsState.palette.displayName,
                    onClick = { onOpenSheet(SettingsSheet.Theme) },
                    divider = false,
                )
                SettingsRow(
                    label = strings.themeFontSection,
                    value = settingsState.font.displayName,
                    onClick = { onOpenSheet(SettingsSheet.Font) },
                )
                SettingsRow(
                    label = strings.settingsSurfaceTitle,
                    value = settingsState.surfaces.label(strings),
                    onClick = { onOpenSheet(SettingsSheet.Surface) },
                )
                SettingsRow(
                    label = strings.settingsStyleTitle,
                    value = settingsState.themeStyle.displayName,
                    // MONO always renders Monochrome, so the style choice does
                    // not apply. The stored value is left untouched.
                    onClick = if (settingsState.palette == AppPalette.MONO) {
                        null
                    } else {
                        { onOpenSheet(SettingsSheet.Style) }
                    },
                    valueColor = if (settingsState.palette == AppPalette.MONO) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        item(key = "images") {
            OptionGroup(title = strings.settingsImagesSection) {
                SettingsRow(
                    label = strings.settingsImageQualityTitle,
                    value = imageQualityLabel(settingsState.imageQuality, strings),
                    onClick = { onOpenSheet(SettingsSheet.ImageQuality) },
                    divider = false,
                )
            }
        }
    }
}
