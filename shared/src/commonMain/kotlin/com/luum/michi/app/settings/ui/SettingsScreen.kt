package com.luum.michi.app.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.MichiBuildConfig
import com.luum.michi.app.accounts.ui.state.AccountsUiState
import com.luum.michi.app.core.language.domain.AppLanguage
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.core.medialist.domain.ScoreFormat
import com.luum.michi.app.core.medialist.domain.label
import com.luum.michi.app.core.model.ImageQuality
import com.luum.michi.app.core.session.domain.Viewer
import com.luum.michi.app.settings.domain.model.ListSort
import com.luum.michi.app.settings.domain.model.StaffNameLanguage
import com.luum.michi.app.settings.domain.model.StartTabOption
import com.luum.michi.app.settings.domain.model.TitleLanguage
import com.luum.michi.app.settings.domain.model.label as settingsLabel
import com.luum.michi.app.settings.ui.model.SettingsPage
import com.luum.michi.app.settings.ui.model.StartDestinationChoice
import com.luum.michi.app.settings.ui.model.shortVersion
import com.luum.michi.app.settings.ui.model.versionLabel
import com.luum.michi.app.settings.ui.state.SettingsState
import com.luum.michi.app.ui.components.ModalSheet
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.components.OptionList
import com.luum.michi.app.ui.components.PickerSheet
import com.luum.michi.app.ui.components.PullRefresh
import com.luum.michi.app.ui.components.SettingsRow
import com.luum.michi.app.ui.components.rememberClipboardWriter
import com.luum.michi.app.ui.language.Strings
import com.luum.michi.app.ui.theme.AppFont
import com.luum.michi.app.ui.theme.AppPalette
import com.luum.michi.app.ui.theme.AppPaletteStyle
import com.luum.michi.app.ui.theme.LocalWarning
import com.luum.michi.app.ui.theme.SurfaceStyle
import com.luum.michi.app.ui.theme.ThemeType
import com.luum.michi.app.ui.theme.displayName
import com.luum.michi.app.ui.theme.fontFamilyFor
import kotlinx.coroutines.delay

/**
 * App settings host. Root owns the toolbar (back + title, no actions); this
 * screen draws the index, its subpages and the sheet host. Synced values live
 * in [settingsState]; only sheet visibility, the copied-version notice and page
 * navigation (in Root's State) are UI state.
 */
@Composable
internal fun SettingsScreen(
    settingsState: SettingsState,
    startDestination: StartDestinationChoice = StartDestinationChoice(),
    viewer: Viewer?,
    accounts: AccountsUiState? = null,
    page: SettingsPage = SettingsPage.Main,
    onOpenPage: (SettingsPage) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    bottomPadding: Dp,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    var openSheet: SettingsSheet? by remember { mutableStateOf(null) }
    var versionCopied by remember { mutableStateOf(false) }
    val writeClipboard = rememberClipboardWriter()

    LaunchedEffect(page) { openSheet = null }
    // An account switch can leave a page that no longer applies (Notifications
    // exists only for AniList). Return to the index instead of painting a blank
    // page under a stale title.
    LaunchedEffect(page, settingsState.canSync) {
        if (page == SettingsPage.Notifications && !settingsState.canSync) {
            onOpenPage(SettingsPage.Main)
        }
    }
    LaunchedEffect(versionCopied) {
        if (versionCopied) {
            delay(3_000)
            versionCopied = false
        }
    }

    val versionText = versionLabel(MichiBuildConfig.Version, MichiBuildConfig.VersionCode)
    val sponsorUrl = MichiBuildConfig.SponsorUrl.takeIf { it.isNotBlank() }

    val content: @Composable () -> Unit = {
        when (page) {
            SettingsPage.General -> SettingsGeneralPage(
                settingsState = settingsState,
                startDestination = startDestination,
                bottomPadding = bottomPadding,
                onOpenSheet = { openSheet = it },
                strings = strings,
                modifier = modifier,
            )
            SettingsPage.Appearance -> SettingsAppearancePage(
                settingsState = settingsState,
                bottomPadding = bottomPadding,
                onOpenSheet = { openSheet = it },
                strings = strings,
                modifier = modifier,
            )
            SettingsPage.MyLists -> SettingsMyListsPage(
                settingsState = settingsState,
                bottomPadding = bottomPadding,
                onOpenSheet = { openSheet = it },
                strings = strings,
                modifier = modifier,
            )
            SettingsPage.Notifications -> SettingsNotificationsPage(
                settingsState = settingsState,
                bottomPadding = bottomPadding,
                strings = strings,
                modifier = modifier,
            )
            SettingsPage.WhatsNew -> WhatsNewScreen(
                bottomPadding = bottomPadding,
                strings = strings,
                modifier = modifier,
            )
            else -> SettingsIndex(
                settingsState = settingsState,
                viewer = viewer,
                accounts = accounts,
                versionText = versionText,
                versionCopied = versionCopied,
                sponsorUrl = sponsorUrl,
                onCopyVersion = {
                    writeClipboard(versionText)
                    versionCopied = true
                },
                onOpenPage = onOpenPage,
                onOpenUrl = onOpenUrl,
                bottomPadding = bottomPadding,
                strings = strings,
                modifier = modifier,
            )
        }
    }

    val refreshable = page == SettingsPage.Main ||
        page == SettingsPage.General ||
        page == SettingsPage.MyLists ||
        page == SettingsPage.Notifications
    if (refreshable && settingsState.canSync) {
        PullRefresh(
            isRefreshing = settingsState.isRefreshing,
            onRefresh = { settingsState.refresh(force = true) },
        ) {
            content()
        }
    } else {
        content()
    }

    if (openSheet == SettingsSheet.Language) {
        PickerSheet(
            title = strings.languageLabel,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = AppLanguage.available,
            selected = settingsState.language,
            label = { it.displayName },
            onSelect = { settingsState.language = it },
        )
    }

    if (openSheet == SettingsSheet.Theme) {
        ModalSheet(
            title = strings.settingsThemeTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
        ) {
            ThemeModeSection(
                themeType = settingsState.themeType,
                onThemeTypeChange = { settingsState.themeType = it },
                strings = strings,
            )
            Spacer(modifier = Modifier.height(16.dp))
            ThemePaletteSection(
                palette = settingsState.palette,
                onPaletteChange = { settingsState.palette = it },
                strings = strings,
            )
        }
    }

    if (openSheet == SettingsSheet.Font) {
        PickerSheet(
            title = strings.themeFontSection,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = AppFont.entries,
            selected = settingsState.font,
            label = { it.displayName },
            onSelect = { settingsState.font = it },
            leading = { option ->
                Text(
                    text = "Ag",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = fontFamilyFor(option),
                    ),
                )
            },
        )
    }

    if (openSheet == SettingsSheet.Surface) {
        PickerSheet(
            title = strings.settingsSurfaceTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = SurfaceStyle.entries,
            selected = settingsState.surfaces,
            label = { it.label(strings) },
            onSelect = { settingsState.surfaces = it },
        )
    }

    if (openSheet == SettingsSheet.Style) {
        PickerSheet(
            title = strings.settingsStyleTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = AppPaletteStyle.entries,
            selected = settingsState.themeStyle,
            label = { it.displayName },
            onSelect = { settingsState.themeStyle = it },
        )
    }

    if (openSheet == SettingsSheet.ImageQuality) {
        PickerSheet(
            title = strings.settingsImageQualityTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = ImageQuality.entries,
            selected = settingsState.imageQuality,
            label = { imageQualityLabel(it, strings) },
            onSelect = { settingsState.imageQuality = it },
        )
    }

    if (openSheet == SettingsSheet.StartTab) {
        PickerSheet(
            title = strings.settingsStartTabTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = StartTabOption.entries,
            selected = startDestination.tab,
            label = { it.settingsLabel(strings) },
            onSelect = { settingsState.startTab = it },
        )
    }

    if (openSheet == SettingsSheet.StartSection) {
        val sectionTitle = startDestination.sectionTitle
        val choices = startDestination.sectionChoices
        if (sectionTitle != null && choices.isNotEmpty()) {
            PickerSheet(
                title = sectionTitle,
                dismissLabel = strings.dismissAction,
                onDismiss = { openSheet = null },
                options = choices,
                selected = choices.first(),
                label = { it.label },
                onSelect = { settingsState.setStartSection(it.value) },
                isSelected = { it.value == startDestination.sectionValue },
            )
        }
    }

    if (openSheet == SettingsSheet.TitleLanguage) {
        PickerSheet(
            title = strings.settingsTitleLanguageTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = TitleLanguage.entries,
            selected = settingsState.titleLanguage,
            label = { it.settingsLabel(strings) },
            onSelect = { settingsState.titleLanguage = it },
        )
    }

    if (openSheet == SettingsSheet.StaffNameLanguage) {
        PickerSheet(
            title = strings.settingsStaffNameTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = StaffNameLanguage.entries,
            selected = settingsState.staffNameLanguage,
            label = { it.settingsLabel(strings) },
            onSelect = { settingsState.staffNameLanguage = it },
        )
    }

    if (openSheet == SettingsSheet.ScoreFormat) {
        PickerSheet(
            title = strings.settingsScoreFormatTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = ScoreFormat.entries,
            selected = settingsState.scoreFormat,
            label = { it.label(strings) },
            onSelect = { settingsState.scoreFormat = it },
        )
    }

    if (openSheet == SettingsSheet.Sort) {
        PickerSheet(
            title = strings.settingsListSortTitle,
            dismissLabel = strings.dismissAction,
            onDismiss = { openSheet = null },
            options = ListSort.entries,
            selected = settingsState.listSort,
            label = { it.settingsLabel(strings) },
            onSelect = { settingsState.listSort = it },
        )
    }
}

@Composable
private fun SettingsIndex(
    settingsState: SettingsState,
    viewer: Viewer?,
    accounts: AccountsUiState?,
    versionText: String,
    versionCopied: Boolean,
    sponsorUrl: String?,
    onCopyVersion: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenUrl: (String) -> Unit,
    bottomPadding: Dp,
    strings: LanguageStrings,
    modifier: Modifier,
) {
    val accountValue = if (viewer == null) {
        strings.settingsSignInTitle
    } else {
        accounts?.summary?.takeIf { it.isNotBlank() }
            ?: accounts?.label(viewer.provider)
            ?: viewer.provider.value
    }
    val lostAccess = accounts?.lostAccess?.let { provider ->
        strings.settingsAccountLostAccess(accounts.label(provider))
    }

    SettingsLazyColumn(bottomPadding = bottomPadding, modifier = modifier) {
        item(key = "account") {
            OptionGroup(title = strings.settingsAccountSection) {
                SettingsRow(
                    label = strings.accountsTitle,
                    // A lost account owns the row: the warning gets the width
                    // under the label instead of competing with the summary.
                    value = if (lostAccess != null) "" else accountValue,
                    supportingText = lostAccess,
                    supportingColor = LocalWarning.current,
                    onClick = { onOpenPage(SettingsPage.Accounts) },
                    divider = false,
                )
            }
        }
        item(key = "preferences") {
            OptionGroup(title = strings.settingsPreferencesSection) {
                SettingsRow(
                    label = strings.settingsGeneralSection,
                    value = "",
                    onClick = { onOpenPage(SettingsPage.General) },
                    divider = false,
                )
                SettingsRow(
                    label = strings.settingsAppearanceTitle,
                    value = settingsState.themeType.label(strings),
                    onClick = { onOpenPage(SettingsPage.Appearance) },
                )
                if (viewer != null) {
                    SettingsRow(
                        label = strings.settingsMyListsTitle,
                        value = "",
                        onClick = { onOpenPage(SettingsPage.MyLists) },
                    )
                }
                if (settingsState.canSync) {
                    SettingsRow(
                        label = strings.notificationsSection,
                        value = "",
                        onClick = { onOpenPage(SettingsPage.Notifications) },
                    )
                }
            }
        }
        item(key = "information") {
            OptionGroup(title = strings.settingsInformationSection) {
                SettingsRow(
                    label = strings.settingsWhatsNewTitle,
                    value = shortVersion(MichiBuildConfig.Version),
                    onClick = { onOpenPage(SettingsPage.WhatsNew) },
                    divider = false,
                )
                SettingsRow(
                    label = strings.aboutReportIssueTitle,
                    value = "↗",
                    onClick = { onOpenUrl(AboutLinks.NewIssue) },
                    supportingText = strings.settingsReportIssueSubtitle,
                    showChevron = false,
                )
                SettingsRow(
                    label = strings.settingsAboutTitle,
                    value = "",
                    onClick = { onOpenPage(SettingsPage.About) },
                )
                SettingsRow(
                    label = strings.settingsAboutVersionLabel,
                    value = versionText,
                    supportingText = if (versionCopied) strings.settingsVersionCopied else null,
                    supportingColor = if (versionCopied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = onCopyVersion,
                    showChevron = false,
                )
                if (sponsorUrl != null) {
                    SettingsRow(
                        label = strings.settingsSupportTitle,
                        value = "↗",
                        onClick = { onOpenUrl(sponsorUrl) },
                        showChevron = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSection(
    themeType: ThemeType,
    onThemeTypeChange: (ThemeType) -> Unit,
    strings: LanguageStrings,
    modifier: Modifier = Modifier,
) {
    OptionList(
        options = ThemeType.entries,
        selected = themeType,
        label = { it.label(strings) },
        onSelect = { onThemeTypeChange(it) },
        title = strings.themeModeSection,
        modifier = modifier,
    )
}

@Composable
private fun ThemePaletteSection(
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    strings: LanguageStrings,
    modifier: Modifier = Modifier,
) {
    OptionList(
        options = AppPalette.entries,
        selected = palette,
        label = { it.displayName },
        onSelect = { onPaletteChange(it) },
        title = strings.themePaletteSection,
        modifier = modifier,
        leading = { option ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(option.seed),
            )
        },
    )
}

/** The one settings sheet open at a time; null = none. */
internal enum class SettingsSheet {
    Language,
    Theme,
    Font,
    Surface,
    Style,
    ImageQuality,
    StartTab,
    StartSection,
    TitleLanguage,
    StaffNameLanguage,
    ScoreFormat,
    Sort,
}

internal fun ThemeType.label(strings: LanguageStrings): String = when (this) {
    ThemeType.SYSTEM -> strings.settingsThemeSystem
    ThemeType.LIGHT -> strings.settingsThemeLight
    ThemeType.DARK -> strings.settingsThemeDark
}

internal fun SurfaceStyle.label(strings: LanguageStrings): String = when (this) {
    SurfaceStyle.GLASS -> strings.surfaceGlassLabel
    SurfaceStyle.SOLID -> strings.surfaceSolidLabel
}

internal fun imageQualityLabel(quality: ImageQuality, strings: LanguageStrings): String = when (quality) {
    ImageQuality.AUTO -> strings.settingsImageQualityAuto
    ImageQuality.HIGH -> strings.settingsImageQualityHigh
    ImageQuality.LOW -> strings.settingsImageQualityLow
}
