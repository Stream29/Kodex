package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusCursor
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * A focusable action surface with shared Enter, Space, and primary-pointer activation semantics.
 *
 * [content] receives the current focus, hover, and press state so it can render an appropriate
 * treatment. Focus selection, pointer focus, traversal, and cursor ownership are provided by the
 * Mosaic runtime.
 */
@Composable
public fun TuiPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    content: @Composable (isFocused: Boolean, isHovered: Boolean, isPressed: Boolean) -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier) {
            content(false, false, false)
        }
        return
    }

    val interaction = remember { TuiPressableInteraction() }
    var isFocused by remember { mutableStateOf(false) }
    val latestOnClick = rememberUpdatedState(onClick)
    val latestOnKeyEvent = rememberUpdatedState(onKeyEvent)
    val requesterModifier = if (focusRequester == null) {
        Modifier
    } else {
        Modifier.focusRequester(focusRequester)
    }

    Box(
        modifier = modifier
            .then(requesterModifier)
            .onFocusChanged { state -> isFocused = state == FocusState.Active }
            .focusable(autoFocus = autoFocus)
            .focusCursor(column = 0)
            .onKeyEvent { event ->
                when {
                    latestOnKeyEvent.value?.invoke(event) == true -> true
                    event == Enter || event == Space -> {
                        latestOnClick.value()
                        true
                    }

                    else -> false
                }
            }
            .onPlaced { coordinates ->
                interaction.size = coordinates.size
            }
            .onPointerHover(
                onPointerEnter = { interaction.hovered = true },
                onPointerExit = { interaction.hovered = false },
            )
            .onPointerEvent { event ->
                when (event.type) {
                    MouseEvent.Type.Press -> {
                        if (event.button != MouseEvent.Button.Left) return@onPointerEvent false
                        interaction.pointerDown = true
                        interaction.pressed = true
                        true
                    }

                    MouseEvent.Type.Drag -> {
                        if (!interaction.pointerDown) return@onPointerEvent false
                        interaction.pressed = interaction.contains(event.position)
                        true
                    }

                    MouseEvent.Type.Release -> {
                        if (!interaction.pointerDown) return@onPointerEvent false
                        val shouldClick = interaction.contains(event.position)
                        interaction.pointerDown = false
                        interaction.pressed = false
                        if (shouldClick) latestOnClick.value()
                        true
                    }

                    MouseEvent.Type.Motion -> false
                }
            },
    ) {
        content(isFocused, interaction.hovered, interaction.pressed)
    }
}

@Stable
private class TuiPressableInteraction {
    var hovered: Boolean by mutableStateOf(false)

    var pressed: Boolean by mutableStateOf(false)

    var pointerDown: Boolean = false

    var size: IntSize = IntSize(0, 0)

    fun contains(position: IntOffset): Boolean =
        position.x in 0 until size.width && position.y in 0 until size.height
}

private val Enter: KeyEvent = KeyEvent("Enter")
private val Space: KeyEvent = KeyEvent(" ")
