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
 * uses inverse video, and a disabled button is dim. Enter, Space, and pointer activation all
 * converge on the same command.
 */
@Composable
public fun TuiButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
) {
    TuiPressable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        focusRequester = focusRequester,
        onKeyEvent = onKeyEvent,
        autoFocus = autoFocus,
    ) { _, isHovered, isPressed ->
        val textStyle = when {
            isPressed -> TextStyle.Invert
            isHovered -> TextStyle.Bold
            enabled -> TextStyle.Unspecified
            else -> TextStyle.Dim
        }
        Text(
            value = "[$label]",
            color = color,
            textStyle = textStyle,
        )
    }
}
