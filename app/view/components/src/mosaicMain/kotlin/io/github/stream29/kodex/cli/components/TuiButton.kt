package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset

/**
 * Compact bracketed command surface with terminal-native interaction feedback.
 *
 * Focus is represented by the terminal cursor, hover uses bold text, a held primary-pointer press
 * combines inverse video and bold by default, [selected] uses inverse video, and a disabled button
 * is dim. [interactionStyle] can preserve semantic foreground/container pairs instead. Enter,
 * Space, and primary-pointer activation converge on the same command. [idleTextStyle] applies while
 * the enabled button is neither selected, hovered, nor pressed. [onSecondaryClick] optionally
 * handles the secondary pointer button, Shift+F10, and the Menu/Application key. Its position is
 * local to this button for pointer activation and `null` for keyboard activation.
 */
@Composable
public fun TuiButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
    onClick: () -> Unit,
) {
    TuiButtonContent(
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
        onSecondaryClick = onSecondaryClick,
        selected = selected,
        idleTextStyle = idleTextStyle,
        interactionStyle = interactionStyle,
        onClick = onClick,
    ) { resolvedTextStyle ->
        Text(
            value = "[$label]",
            color = color,
            textStyle = resolvedTextStyle,
        )
    }
}

/** [TuiButton] overload retaining spans in a structured label. */
@Composable
public fun TuiButton(
    label: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: ((IntOffset?) -> Unit)? = null,
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
    onClick: () -> Unit,
) {
    val bracketedLabel = buildAnnotatedString {
        append("[")
        append(label)
        append("]")
    }
    TuiButtonContent(
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
        onSecondaryClick = onSecondaryClick,
        selected = selected,
        idleTextStyle = idleTextStyle,
        interactionStyle = interactionStyle,
        onClick = onClick,
    ) { resolvedTextStyle ->
        Text(
            value = bracketedLabel,
            color = color,
            textStyle = resolvedTextStyle,
        )
    }
}

@Composable
private fun TuiButtonContent(
    modifier: Modifier,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onKeyEvent: ((KeyEvent) -> Boolean)?,
    autoFocus: Boolean,
    onSecondaryClick: ((IntOffset?) -> Unit)?,
    selected: Boolean,
    idleTextStyle: TextStyle,
    interactionStyle: TuiInteractionStyle,
    onClick: () -> Unit,
    content: @Composable (TextStyle) -> Unit,
) {
    TuiPressable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
        onSecondaryClick = onSecondaryClick,
    ) { _, isHovered, isPressed ->
        val resolvedTextStyle = tuiInteractionTextStyle(
            enabled = enabled,
            hovered = isHovered,
            pressed = isPressed,
            selected = selected,
            idleTextStyle = idleTextStyle,
            interactionStyle = interactionStyle,
        )
        content(resolvedTextStyle)
    }
}
