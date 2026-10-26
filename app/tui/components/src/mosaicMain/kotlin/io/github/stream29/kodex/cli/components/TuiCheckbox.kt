package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset

@Composable
public actual fun TuiCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    color: Color,
    idleTextStyle: TextStyle,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onKeyEvent: ((KeyEvent) -> Boolean)?,
    autoFocus: Boolean,
    onSecondaryClick: ((IntOffset?) -> Unit)?,
    interactionStyle: TuiInteractionStyle,
) {
    TuiPressable(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
        onSecondaryClick = onSecondaryClick,
    ) { _, isHovered, isPressed ->
        Text(
            value = "[${if (checked) "x" else " "}] $label",
            color = color,
            textStyle = tuiInteractionTextStyle(
                enabled = enabled,
                hovered = isHovered,
                pressed = isPressed,
                idleTextStyle = idleTextStyle,
                interactionStyle = interactionStyle,
            ),
        )
    }
}
