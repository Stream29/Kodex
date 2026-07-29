package io.github.stream29.codex.lite.cli.components

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier

/**
 * Interprets unmodified Page Up and Page Down keys as viewport-sized vertical scroll requests.
 *
 * The event is consumed only when [state] consumes a non-zero number of rows. [viewportSize]
 * should report the latest measured viewport and is read when the key event arrives.
 *
 * @param interactionSource `null` when the caller does not observe scroll interactions.
 */
public fun Modifier.scrollablePaging(
    state: ScrollableState,
    viewportSize: () -> Int,
    enabled: Boolean = true,
    reverseDirection: Boolean = false,
    interactionSource: MutableScrollInteractionSource? = null,
): Modifier {
    if (!enabled) return this
    return onKeyEvent { event ->
        if (event.alt || event.ctrl || event.shift) return@onKeyEvent false
        val direction = when (event.key) {
            "PageUp" -> -1
            "PageDown" -> 1
            else -> return@onKeyEvent false
        }
        val requestedDelta = direction * viewportSize().coerceAtLeast(1) *
            if (reverseDirection) -1 else 1
        val consumedDelta = state.scrollBy(requestedDelta)
        if (consumedDelta == 0) return@onKeyEvent false
        interactionSource?.tryEmit(
            ScrollInteraction(
                source = ScrollInputSource.Keyboard,
                orientation = ScrollOrientation.Vertical,
                requestedDelta = requestedDelta,
                consumedDelta = consumedDelta,
            ),
        )
        true
    }
}

