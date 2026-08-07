package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Compact bracketed command surface with terminal-native interaction feedback.
 *
 * Focus is represented by the terminal cursor, hover uses bold text, a held primary-pointer press
 * uses inverse video, and a disabled button is dim. Enter, Space, and primary-pointer activation
 * converge on the same command. [idleTextStyle] applies while the enabled button is neither hovered
 * nor pressed. [onSecondaryClick] optionally handles the secondary pointer button and Shift+F10.
 */
@Composable
public fun TuiButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    idleTextStyle: TextStyle = TextStyle.Unspecified,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onSecondaryClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    TuiPressable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
        onSecondaryClick = onSecondaryClick?.let { callback -> { _ -> callback() } },
    ) { _, isHovered, isPressed ->
        val resolvedTextStyle = when {
            isPressed -> TextStyle.Invert
            isHovered -> TextStyle.Bold
            enabled -> idleTextStyle
            else -> TextStyle.Dim
        }
        Text(
            value = "[$label]",
            color = color,
            textStyle = resolvedTextStyle,
        )
    }
}
