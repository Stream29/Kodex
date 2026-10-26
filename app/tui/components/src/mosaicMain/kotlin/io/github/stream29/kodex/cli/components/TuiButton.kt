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

@Composable
public actual fun TuiButton(
    label: String,
    modifier: Modifier,
    color: Color,
    idleTextStyle: TextStyle,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onKeyEvent: ((KeyEvent) -> Boolean)?,
    autoFocus: Boolean,
    onSecondaryClick: ((IntOffset?) -> Unit)?,
    interactionStyle: TuiInteractionStyle,
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

@Composable
public actual fun TuiButton(
    label: AnnotatedString,
    modifier: Modifier,
    color: Color,
    idleTextStyle: TextStyle,
    selected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onKeyEvent: ((KeyEvent) -> Boolean)?,
    autoFocus: Boolean,
    onSecondaryClick: ((IntOffset?) -> Unit)?,
    interactionStyle: TuiInteractionStyle,
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
