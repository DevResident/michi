package com.luum.michi.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Generic chip rail: section pills for the lists, year pills in filter
 * sheets, platform chips on calendar cards. Generic over the tab type so
 * features never depend on each other: each caller passes its own tabs in
 * and gets its own type back on select. Labels arrive translated; a null
 * [ChipTab.count] renders the label alone (display-only chips), a null
 * [selected] leaves every pill unhighlighted. This composable owns only
 * the pill language (selected = primary container, rest = outlined).
 *
 * [interactive] false renders **flat, inert** chips (`surface` + `outlineVariant`
 * border, no glass, no click/ripple): they are labels (genres, tags, roles,
 * occupations, studios), not buttons. The interactive recipe is unchanged.
 *
 * [fill] true spreads the interactive chips equally across one fixed row when
 * they all fit at the widest label's width. The fit decision comes from the
 * real ChipPill measurables (`maxIntrinsicWidth`), never from copied text
 * metrics, so a change to the chip recipe cannot silently break the rail. When
 * they do not fit — narrow screen, large font, long label — the rail falls back
 * to the normal scrolling [LazyRow] instead of clipping a chip.
 */
data class ChipTab<T>(
    val value: T,
    val label: String,
    val count: Int? = null,
    // Platform logo (calendar chips): 16dp image ahead of the label,
    // hidden when null, blank or failed (text alone still reads).
    val iconUrl: String? = null,
    // Display-only chips (tags): semantic emphasis, translated to a theme color
    // by [FlatChip]. Interactive chips ignore it.
    val emphasis: ChipEmphasis = ChipEmphasis.DEFAULT,
    // Accessibility-only prefix (e.g. "Lista que sigues: AniList"). The visual
    // label stays short; TalkBack/VoiceOver read this plus the selected state.
    val accessibilityLabel: String? = null,
)

/** Semantic emphasis of a flat chip; the actual theme color lives in [FlatChip]. */
enum class ChipEmphasis { DEFAULT, ALERT }

private val ChipSpacing = 8.dp

/**
 * True when every chip fits at the widest chip's width, including the gaps and
 * the rail's horizontal content padding. The composable asks the real pills for
 * their intrinsic widths and delegates the arithmetic here so it can be tested
 * without composition.
 */
internal fun chipRailFits(
    availableWidth: Dp,
    chipWidths: List<Dp>,
    spacing: Dp = ChipSpacing,
    horizontalPadding: Dp = 0.dp,
): Boolean {
    if (chipWidths.isEmpty()) return false
    val widest = chipWidths.maxOf { it.value }
    val required = widest * chipWidths.size +
        spacing.value * (chipWidths.size - 1) +
        horizontalPadding.value * 2
    return required <= availableWidth.value
}

@Composable
internal fun <T> ChipRail(
    tabs: List<ChipTab<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Baked 16dp matches the full-width list screens; sheets (already
    // inset) pass zero to avoid double padding.
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    // Compact chips (cards): tighter padding + smaller type so the bottom
    // edge lands where the list pills land. Defaults keep every other
    // caller byte-identical.
    compact: Boolean = false,
    // Display-only chips: flat surface + outline, no ripple.
    interactive: Boolean = true,
    // Spread the chips equally on one row when they fit; scroll otherwise.
    fill: Boolean = false,
) {
    if (!chipRailUsesFill(fill = fill, interactive = interactive, tabs = tabs)) {
        LazyChipRail(
            tabs = tabs,
            selected = selected,
            onSelect = onSelect,
            modifier = modifier,
            contentPadding = contentPadding,
            compact = compact,
            interactive = interactive,
        )
        return
    }
    FillChipRail(
        tabs = tabs,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        contentPadding = contentPadding,
        compact = compact,
    )
}

/**
 * Fill mode measures the real pills before choosing equal-width cells. A remote
 * icon is loaded asynchronously, so measuring it before the image arrives would
 * decide the layout from an icon-less (or placeholder) width and could then
 * shift or clip the text when the icon appears. Any `iconUrl` therefore keeps
 * the normal `LazyChipRail`, which measures each pill lazily with its real
 * content.
 */
internal fun <T> chipRailUsesFill(
    fill: Boolean,
    interactive: Boolean,
    tabs: List<ChipTab<T>>,
): Boolean = fill &&
    interactive &&
    tabs.isNotEmpty() &&
    tabs.none { !it.iconUrl.isNullOrBlank() }

@Composable
private fun <T> LazyChipRail(
    tabs: List<ChipTab<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    compact: Boolean,
    interactive: Boolean,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        items(
            items = tabs,
            key = { "${it.label}·${it.value}" },
        ) { tab ->
            if (interactive) {
                ChipPill(
                    label = tab.label,
                    selected = tab.value == selected,
                    onClick = { onSelect(tab.value) },
                    compact = compact,
                    iconUrl = tab.iconUrl,
                    count = tab.count,
                    modifier = chipAccessibility(tab, selected),
                )
            } else {
                FlatChip(
                    label = if (tab.count != null) "${tab.label} · ${tab.count}" else tab.label,
                    emphasis = tab.emphasis,
                )
            }
        }
    }
}

private sealed interface FillSlot {
    data class Probe(val index: Int) : FillSlot
    data object Lazy : FillSlot
}

/**
 * Measures each real [ChipPill] intrinsically first. If the equal-width cells
 * can hold the widest one, those same measurables are measured once more with
 * the fixed cell width; otherwise the scrolling rail is composed and measured
 * instead. Measuring the pills themselves is what keeps padding, type, icons
 * and any future recipe change in one source of truth.
 */
@Composable
private fun <T> FillChipRail(
    tabs: List<ChipTab<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    compact: Boolean,
) {
    val layoutDirection = LocalLayoutDirection.current
    SubcomposeLayout(modifier = modifier) { constraints ->
        val start = contentPadding.calculateStartPadding(layoutDirection).roundToPx()
        val end = contentPadding.calculateEndPadding(layoutDirection).roundToPx()
        val top = contentPadding.calculateTopPadding().roundToPx()
        val bottom = contentPadding.calculateBottomPadding().roundToPx()
        val available = (constraints.maxWidth - start - end).coerceAtLeast(0)

        val probes = tabs.mapIndexed { index, tab ->
            subcompose(FillSlot.Probe(index)) {
                ChipPill(
                    label = tab.label,
                    selected = tab.value == selected,
                    onClick = { onSelect(tab.value) },
                    compact = compact,
                    iconUrl = tab.iconUrl,
                    count = tab.count,
                    modifier = chipAccessibility(tab, selected),
                )
            }.single()
        }
        val widths = probes.map { it.maxIntrinsicWidth(constraints.maxHeight).toDp() }
        val fits = chipRailFits(
            availableWidth = available.toDp(),
            chipWidths = widths,
            spacing = ChipSpacing,
        )

        if (fits) {
            val spacing = ChipSpacing.roundToPx()
            val cellWidth = ((available - spacing * (tabs.size - 1)) / tabs.size).coerceAtLeast(0)
            val cellConstraints = Constraints(
                minWidth = cellWidth,
                maxWidth = cellWidth,
            )
            val placeables = probes.map { it.measure(cellConstraints) }
            val contentHeight = placeables.maxOfOrNull { it.height } ?: 0
            layout(constraints.maxWidth, top + contentHeight + bottom) {
                var x = start
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, top)
                    x += cellWidth + spacing
                }
            }
        } else {
            val lazy = subcompose(FillSlot.Lazy) {
                LazyChipRail(
                    tabs = tabs,
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = contentPadding,
                    compact = compact,
                    interactive = true,
                )
            }.single()
            val placeable = lazy.measure(constraints)
            layout(constraints.maxWidth, placeable.height) {
                placeable.place(0, 0)
            }
        }
    }
}

private fun <T> chipAccessibility(tab: ChipTab<T>, selected: T?): Modifier {
    val label = tab.accessibilityLabel ?: return Modifier
    val isSelected = tab.value == selected
    return Modifier.semantics(mergeDescendants = true) {
        contentDescription = label
        this.selected = isSelected
        role = Role.RadioButton
    }
}

/**
 * Flat, inert chip — the one flat-chip recipe (`surface` + `outlineVariant`, no
 * glass, no click). `ChipRail`'s `interactive = false` branch and the person
 * hero's chip `FlowRow` both draw it, so the style never forks. A flat chip
 * carries only a label: `iconUrl`/`compact` belong to the interactive rail.
 */
@Composable
internal fun FlatChip(
    label: String,
    modifier: Modifier = Modifier,
    emphasis: ChipEmphasis = ChipEmphasis.DEFAULT,
) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surface,
        border = flatBorder(),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when (emphasis) {
                ChipEmphasis.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
                ChipEmphasis.ALERT -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
