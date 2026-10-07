package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/** Framework-only exclusive-choice button and wrapped description; owns no business state. */
@Composable
public fun InteractionOption(
    label: String,
    description: String,
    columns: Int,
    selected: Boolean,
    enabled: Boolean,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    TuiButton(
        label = "${if (selected) "●" else "○"} $label",
        enabled = enabled,
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        onClick = onClick,
    )
    description.takeIf(String::isNotBlank)?.let {
        InteractionText("  $it", columns, TextStyle.Dim)
    }
}

/**
 * Renderer buffer keyed by exact owner/input identity. [text] remains authoritative;
 * input edits and Enter are callbacks, never a hidden business draft or auto-submission.
 */
@Composable
public fun InteractionFreeForm(
    ownerKey: Any,
    inputId: String,
    text: String,
    columns: Int,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    focusOnPlacement: Boolean,
    enabled: Boolean,
    allowEmpty: Boolean = false,
    onValueChanged: (String) -> Unit,
    onSubmitted: () -> Unit,
    onFocusRequested: () -> Unit,
) {
    val input = remember(ownerKey, inputId) {
        TextInputState(TextInputValue(text = text, cursorOffset = text.length))
    }
    LaunchedEffect(input, text) {
        if (input.value.text != text) {
            input.reset(TextInputValue(text = text, cursorOffset = text.length))
        }
    }
    TextInput(
        state = input,
        modifier = Modifier.onPlaced {
            if (focusOnPlacement && focusRequester.requestFocus()) onFocusRequested()
        },
        layout = TextInputLayout.create(
            value = input.value,
            width = columns,
            firstLinePrefix = "  > ",
            continuationLinePrefix = "    ",
        ),
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        enabled = enabled,
        onValueChanged = { value -> onValueChanged(value.text) },
        onKeyEvent = { event ->
            if (
                event.key.equals("Enter", ignoreCase = true) &&
                !event.alt && !event.ctrl && !event.shift &&
                (allowEmpty || input.value.text.trim().isNotEmpty())
            ) {
                onSubmitted()
                true
            } else {
                false
            }
        },
    )
}

/** Wrapped text with a minimum one-cell width; no domain formatting or state. */
@Composable
public fun InteractionText(value: String, columns: Int, textStyle: TextStyle) {
    value.wrapToTerminalWidth(columns.coerceAtLeast(1)).forEach { line ->
        Text(value = line, textStyle = textStyle)
    }
}
