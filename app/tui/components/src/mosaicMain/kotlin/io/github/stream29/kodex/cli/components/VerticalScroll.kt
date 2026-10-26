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

public actual fun Modifier.verticalScroll(
    state: ScrollState,
    enabled: Boolean,
    reverseScrolling: Boolean,
    interactionSource: MutableScrollInteractionSource?,
): Modifier = scrollable(
    state = state,
    enabled = enabled,
    reverseDirection = reverseScrolling,
    interactionSource = interactionSource,
).scrollablePaging(
    state = state,
    viewportSize = { state.viewportSize },
    enabled = enabled,
    reverseDirection = reverseScrolling,
    interactionSource = interactionSource,
).then(VerticalScrollBeyondBoundsLayoutElement())
    .then(VerticalScrollBringIntoViewElement(state, reverseScrolling, interactionSource))
    .clipToBounds() then VerticalScrollLayoutModifier(state, reverseScrolling)

private class VerticalScrollLayoutModifier(
    private val state: ScrollState,
    private val reverseScrolling: Boolean,
) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        check(constraints.hasBoundedHeight) {
            "A vertically scrollable component must be measured with a finite maximum height."
        }
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
        )
        val width = constraints.constrainWidth(placeable.width)
        val height = constraints.constrainHeight(placeable.height)
        val maximumScroll = (placeable.height - height).coerceAtLeast(0)
        state.updateBounds(maxValue = maximumScroll, viewportSize = height)
        val scroll = state.value
        val y = if (reverseScrolling) scroll - maximumScroll else -scroll
        return layout(width, height) {
            placeable.place(0, y)
        }
    }

    override fun toString(): String =
        "VerticalScrollLayout(state=$state, reverseScrolling=$reverseScrolling)"
}
