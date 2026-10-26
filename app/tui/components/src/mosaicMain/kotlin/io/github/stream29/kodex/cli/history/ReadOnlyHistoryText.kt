package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainWidth
import com.jakewharton.mosaic.ui.unit.constrainHeight
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth

@Composable
public actual fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle,
    color: Color,
) {
    val layoutCache = remember(value) { WrappedHistoryTextLayoutCache(value) }
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        check(constraints.hasBoundedWidth) {
            "Agent history text must be measured with a finite maximum width."
        }
        val lines = layoutCache.linesFor(constraints.maxWidth.coerceAtLeast(1))
        val placeable = subcompose(WrappedHistoryTextSlot) {
            Column {
                lines.forEach { line -> Text(value = line, color = color, textStyle = textStyle) }
            }
        }.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = constraints.constrainHeight(placeable.height),
        ) { placeable.place(0, 0) }
    }
}

internal class WrappedHistoryTextLayoutCache(private val value: String) {
    private var cachedWidth: Int? = null
    private var cachedLines: List<String> = emptyList()
    internal fun linesFor(width: Int): List<String> {
        if (cachedWidth != width) {
            cachedWidth = width
            cachedLines = value.wrapToTerminalWidth(width)
        }
        return cachedLines
    }
}

private data object WrappedHistoryTextSlot
