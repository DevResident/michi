package com.luum.michi.app.mediaList.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luum.michi.app.ui.components.PillShape
import com.luum.michi.app.ui.components.flatBorder

/**
 * Small "MAL" tag on a MAL-only list card (a work AniList does not have):
 * it sits before the edit pencil and explains why the card opens MAL.
 */
@Composable
internal fun MalOnlyBadge(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surface,
        border = flatBorder(),
        modifier = modifier
            .padding(start = 4.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
