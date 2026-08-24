package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset

/**
 * A terminal checkbox whose marker and label form one pressable setting row.
 *
 * Enter, Space, and primary-pointer activation toggle the value. Focus remains represented by the
 * terminal cursor; hover, press, and disabled states use the shared interaction treatment.
 */
@Composable
public fun TuiCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.body,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
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
