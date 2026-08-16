package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.LayoutModifier
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth

/**
 * Measures content eagerly and exposes a horizontally scrollable terminal-cell viewport.
 *
 * The horizontal axis must have a finite maximum width. Wheel input moves by terminal columns and
 * unmodified PageUp/PageDown move by one viewport.
 */
public fun Modifier.horizontalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
): Modifier = scrollable(
    state = state,
    orientation = ScrollOrientation.Horizontal,
    enabled = enabled,
    reverseDirection = reverseScrolling,
    interactionSource = interactionSource,
).scrollablePaging(
    state = state,
    viewportSize = { state.viewportSize },
    orientation = ScrollOrientation.Horizontal,
    enabled = enabled,
    reverseDirection = reverseScrolling,
    interactionSource = interactionSource,
).clipToBounds() then HorizontalScrollLayoutModifier(state, reverseScrolling)

private class HorizontalScrollLayoutModifier(
    private val state: ScrollState,
    private val reverseScrolling: Boolean,
) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        check(constraints.hasBoundedWidth) {
            "A horizontally scrollable component must be measured with a finite maximum width."
        }
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
            ),
        )
        val width = constraints.constrainWidth(placeable.width)
        val height = constraints.constrainHeight(placeable.height)
        val maximumScroll = (placeable.width - width).coerceAtLeast(0)
        state.updateBounds(maxValue = maximumScroll, viewportSize = width)
        val scroll = state.value
        val x = if (reverseScrolling) scroll - maximumScroll else -scroll
        return layout(width, height) {
            placeable.place(x, 0)
        }
    }

    override fun toString(): String =
        "HorizontalScrollLayout(state=$state, reverseScrolling=$reverseScrolling)"
}
