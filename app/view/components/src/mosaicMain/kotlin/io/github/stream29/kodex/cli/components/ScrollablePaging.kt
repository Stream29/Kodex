package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier

public actual fun Modifier.scrollablePaging(
    state: ScrollableState,
    viewportSize: () -> Int,
    pageSize: (viewportSize: Int) -> Int,
    orientation: ScrollOrientation,
    enabled: Boolean,
    reverseDirection: Boolean,
    interactionSource: MutableScrollInteractionSource?,
): Modifier {
    if (!enabled) return this
    return onKeyEvent { event ->
        if (event.alt || event.ctrl || event.shift) return@onKeyEvent false
        val direction = when (event.key) {
            "PageUp" -> -1
            "PageDown" -> 1
            else -> return@onKeyEvent false
        }
        val viewport = viewportSize().coerceAtLeast(1)
        val requestedDelta = direction * pageSize(viewport).coerceAtLeast(1) *
            if (reverseDirection) -1 else 1
        val consumedDelta = state.scrollBy(requestedDelta)
        if (consumedDelta == 0) return@onKeyEvent false
        interactionSource?.tryEmit(
            ScrollInteraction(
                source = ScrollInputSource.Keyboard,
                orientation = orientation,
                requestedDelta = requestedDelta,
                consumedDelta = consumedDelta,
            ),
        )
        true
    }
}
