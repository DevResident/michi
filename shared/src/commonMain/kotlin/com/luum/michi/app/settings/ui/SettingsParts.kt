package com.luum.michi.app.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.ui.components.PanelShape
import com.luum.michi.app.ui.icons.AppIcons
import com.luum.michi.app.ui.theme.LocalWarning

/** The shared page frame: the same padding and spacing every Settings page had. */
@Composable
internal fun SettingsLazyColumn(
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Inline error with retry, shown only when the last load/save failed. Same frame
 *  language as [OptionGroup] ([PanelShape] + 1dp border) with the border in error
 *  and no fill, so a failed sync reads as an error while aligning with the groups. */
@Composable
internal fun SettingsErrorRow(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The whole card retries (like group rows): Surface carries the tap with
    // its shape-bound ripple, the inner retry row is static layout.
    Surface(
        onClick = onRetry,
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        border = BorderStroke(
            width = 1.dp,
            color = LocalWarning.current,
        ),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = LocalWarning.current,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalWarning.current,
                modifier = Modifier.weight(1f),
            )
            // Same metrics as SettingsRow (12dp vertical, 4dp gap, 20dp
            // chevron): a TextButton carries its own 12dp content padding
            // and 48dp floor, so its chevron can never sit where the
            // groups' chevrons sit.
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = retryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalWarning.current,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = LocalWarning.current,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
