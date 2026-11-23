package com.luum.michi.app.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luum.michi.app.core.language.domain.LanguageStrings
import com.luum.michi.app.ui.components.OptionGroup
import com.luum.michi.app.ui.language.Strings

/**
 * In-app release notes, newest first. Content is bundled with the app: no
 * network, no docs file read at runtime.
 */
@Composable
internal fun WhatsNewScreen(
    bottomPadding: Dp,
    strings: LanguageStrings = Strings.current,
    modifier: Modifier = Modifier,
) {
    SettingsLazyColumn(bottomPadding = bottomPadding, modifier = modifier) {
        strings.releaseNotes.forEach { release ->
            item(key = "notes-${release.version}") {
                OptionGroup(title = "Michi ${release.version}") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        release.sections.forEach { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                section.items.forEach { item ->
                                    Text(
                                        text = "• $item",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
