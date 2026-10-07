package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

/** Pure measured-content layout shared by Agent and draft status bars. */
@Composable
public fun StatusBarLayout(
    columns: Int,
    regularContent: @Composable () -> Unit,
    settingsContent: (@Composable () -> Unit)? = null,
) {
    val width = statusBarWidth(columns)
    Layout(
        content = {
            regularContent()
            settingsContent?.invoke()
        },
        // Sidebars can leave less space than one natural-width control. Keep both drawing and
        // hit testing inside this status bar instead of overflowing the terminal or adjacent pane.
        modifier = Modifier.width(width).clipToBounds(),
        debugInfo = { "StatusBarLayout(columns=$width)" },
    ) { measurables, constraints ->
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = 1,
        )
        val placeables = measurables.map { measurable -> measurable.measure(childConstraints) }
        val settings = if (settingsContent != null) placeables.last() else null
        val regular = if (settings != null) placeables.dropLast(1) else placeables
        val plan = statusBarLayoutPlan(
            width = width,
            itemWidths = regular.map { placeable -> placeable.width },
            settingsWidth = settings?.width ?: 0,
        )
        layout(width = width, height = plan.rowCount) {
            regular.zip(plan.itemPositions).forEach { (placeable, position) ->
                placeable.place(position.x, position.y)
            }
            settings?.place(plan.settingsPosition.x, plan.settingsPosition.y)
        }
    }
}

public fun buttonWidth(label: String): Int = label.terminalCellWidth() + ButtonBorderColumns

public fun statusBarWidth(columns: Int): Int = (columns - 1).coerceAtLeast(1)

public data class StatusBarLayoutPlan(
    public val itemPositions: List<IntOffset>,
    public val settingsPosition: IntOffset,
    public val rowCount: Int,
)

public fun statusBarLayoutPlan(
    width: Int,
    itemWidths: List<Int>,
    settingsWidth: Int,
): StatusBarLayoutPlan {
    require(width > 0) { "Status bar width must be positive." }
    require(settingsWidth >= 0) { "Settings width must be nonnegative." }
    require(itemWidths.all { itemWidth -> itemWidth > 0 }) {
        "Status bar item widths must be positive."
    }
    val settingsX = (width - settingsWidth).coerceAtLeast(0)
    val firstRowLimit = if (settingsWidth == 0) width else
        (settingsX - StatusBarItemSpacing).coerceAtLeast(0)
    var row = 0
    var rowWidth = 0
    val positions = buildList(itemWidths.size) {
        itemWidths.forEach { itemWidth ->
            while (true) {
                val rowLimit = if (row == 0) firstRowLimit else width
                val itemX = if (rowWidth == 0) 0 else rowWidth + StatusBarItemSpacing
                val fits = itemX + itemWidth <= rowLimit
                if (fits || (row > 0 && rowWidth == 0)) {
                    add(IntOffset(itemX, row))
                    rowWidth = itemX + itemWidth
                    break
                }
                row++
                rowWidth = 0
            }
        }
    }
    return StatusBarLayoutPlan(
        itemPositions = positions,
        settingsPosition = IntOffset(settingsX, 0),
        rowCount = maxOf(1, positions.maxOfOrNull(IntOffset::y)?.plus(1) ?: 1),
    )
}

private const val ButtonBorderColumns: Int = 2
private const val StatusBarItemSpacing: Int = 1
