package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent

/**
 * Interprets wheel input as terminal-cell deltas along [orientation] for [state].
 *
 * This modifier does not move, measure, or clip content. It consumes a wheel event only when
 * [ScrollableState.scrollBy] consumes a non-zero delta.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 */
public fun Modifier.scrollable(
    state: ScrollableState,
    orientation: ScrollOrientation = ScrollOrientation.Vertical,
    enabled: Boolean = true,
    reverseDirection: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
    wheelScrollLines: Int = DefaultWheelScrollLines,
): Modifier {
    require(wheelScrollLines > 0) { "Wheel scroll line count must be positive." }
    if (!enabled) return this
    return onPointerEvent { event ->
        if (event.type != MouseEvent.Type.Press) return@onPointerEvent false
        val direction = when (event.button) {
            MouseEvent.Button.WheelUp -> -1
            MouseEvent.Button.WheelDown -> 1
            else -> return@onPointerEvent false
        }
        val requestedDelta = direction * wheelScrollLines * if (reverseDirection) -1 else 1
        val consumedDelta = state.scrollBy(requestedDelta)
        if (consumedDelta == 0) return@onPointerEvent false
        interactionSource?.tryEmit(
            ScrollInteraction(
                source = ScrollInputSource.Pointer,
                orientation = orientation,
                requestedDelta = requestedDelta,
                consumedDelta = consumedDelta,
            ),
        )
        true
    }
}

private const val DefaultWheelScrollLines: Int = 3
