package com.luum.michi.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room a screen must leave below its last item so a [GlassBar] never covers it:
 * the 48dp bar content + the 24dp bottom margin + the usual 16dp list tail.
 */
internal val GlassBarClearance: Dp = 96.dp

/**
 * Bottom floating capsule shared by the calendar date bar and the Compare lists
 * apply bar: full-width Box, 16dp side margins, 24dp bottom margin, glass
 * capsule + inner 8dp padding. Callers own the content and the bottom alignment;
 * this owns only the geometry so the two bars can never drift apart.
 */
@Composable
internal fun GlassBar(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        GlassCapsule(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
