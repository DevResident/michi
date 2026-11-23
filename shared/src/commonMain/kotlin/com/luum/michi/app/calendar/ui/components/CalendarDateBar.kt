package com.luum.michi.app.calendar.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.ui.components.GlassBar
import com.luum.michi.app.ui.components.GlassCircleButton
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.language.Strings

/**
 * Bottom date bar: previous-day chevron, date button (opens the system
 * calendar), next-day chevron. Same floating full-width capsule language
 * as the [TabBar]: centered box, 16dp sides, 24dp bottom margin, content
 * scrolls behind it. The date cell is caller-owned (the picker field
 * hosts its own dialog, so it arrives as a slot).
 */
@Composable
internal fun CalendarDateBar(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
    dateContent: @Composable RowScope.() -> Unit,
) {
    GlassBar(modifier = modifier) {
        GlassCircleButton(
            icon = AppIcons.Back,
            contentDescription = strings.calendarPreviousDayAction,
            onClick = onPrevious,
            enabled = previousEnabled,
        )
        dateContent()
        GlassCircleButton(
            icon = AppIcons.ChevronRight,
            contentDescription = strings.calendarNextDayAction,
            onClick = onNext,
            enabled = nextEnabled,
        )
    }
}
