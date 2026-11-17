package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class StatusItemPosition(val x: Int, val row: Int)

internal fun statusItemPositions(
    widths: List<Int>, canvasWidth: Int, settingsWidth: Int, gap: Int,
): List<StatusItemPosition> {
    var row = 0
    var x = 0
    return widths.map { width ->
        val limit = if (row == 0 && settingsWidth > 0) {
            (canvasWidth - settingsWidth - gap).coerceAtLeast(0)
        } else canvasWidth
        val next = if (x == 0) 0 else x + gap
        if (next + width > limit && (row == 0 || x > 0)) {
            row++
            x = 0
        } else {
            x = next
        }
        StatusItemPosition(x, row).also { x += width }
    }
}

@Composable
internal fun StatusBarLayout(regular: @Composable () -> Unit, settings: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val canvasWidth = maxOf(maxWidth, 96.dp)
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        SubcomposeLayout(
            Modifier.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || scroll.maxValue == 0) false
                else {
                    val direction = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    if (direction == 0) false else {
                        scope.launch { scroll.scrollBy(direction * 48f) }
                        true
                    }
                }
            }.horizontalScroll(scroll).focusable().width(canvasWidth),
        ) { constraints ->
            val width = constraints.maxWidth
            val childConstraints = Constraints(maxWidth = width)
            val gap = 8.dp.roundToPx()
            val rowGap = 4.dp.roundToPx()
            val settingsItems = subcompose("settings", settings).map { it.measure(childConstraints) }
            val settingsWidth = settingsItems.sumOf { it.width } + gap * (settingsItems.size - 1).coerceAtLeast(0)
            val items = subcompose("regular", regular).map { it.measure(childConstraints) }
            val positions = statusItemPositions(items.map { it.width }, width, settingsWidth, gap)
            val rowCount = maxOf(1, (positions.maxOfOrNull { it.row } ?: 0) + 1)
            val heights = IntArray(rowCount)
            settingsItems.forEach { heights[0] = maxOf(heights[0], it.height) }
            items.forEachIndexed { index, item ->
                val row = positions[index].row
                heights[row] = maxOf(heights[row], item.height)
            }
            val y = IntArray(rowCount)
            for (row in 1 until rowCount) y[row] = y[row - 1] + heights[row - 1] + rowGap
            val height = y.last() + heights.last()
            layout(width, constraints.constrainHeight(height)) {
                items.forEachIndexed { index, item ->
                    val position = positions[index]
                    item.placeRelative(position.x, y[position.row])
                }
                var settingsX = (width - settingsWidth).coerceAtLeast(0)
                settingsItems.forEach { item ->
                    item.placeRelative(settingsX, 0)
                    settingsX += item.width + gap
                }
            }
        }
    }
}
