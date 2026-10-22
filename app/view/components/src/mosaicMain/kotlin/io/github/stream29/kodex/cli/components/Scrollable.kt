package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent

public actual fun Modifier.scrollable(
    state: ScrollableState,
    orientation: ScrollOrientation,
    enabled: Boolean,
    reverseDirection: Boolean,
    interactionSource: MutableScrollInteractionSource?,
    wheelScrollLines: Int,
): Modifier {
    require(wheelScrollLines > 0) { "Wheel scroll line count must be positive." }
    if (!enabled) return this
    return onPointerEvent { event ->
        if (event.type != MouseEvent.Type.Press) return@onPointerEvent false
        val direction = when (event.button) {
            MouseEvent.Button.WheelUp -> -1
            MouseEvent.Button.WheelDown -> 1
            MouseEvent.Button.WheelLeft -> {
                if (orientation != ScrollOrientation.Horizontal) return@onPointerEvent false
                -1
            }

            MouseEvent.Button.WheelRight -> {
                if (orientation != ScrollOrientation.Horizontal) return@onPointerEvent false
                1
            }

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
