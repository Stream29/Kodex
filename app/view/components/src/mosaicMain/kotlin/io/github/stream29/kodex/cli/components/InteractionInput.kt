package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

@Composable
public actual fun InteractionOption(
    label: String,
    description: String,
    columns: Int,
    selected: Boolean,
    enabled: Boolean,
    autoFocus: Boolean,
    focusRequester: FocusRequester?,
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

@Composable
public actual fun InteractionFreeForm(
    ownerKey: Any,
    inputId: String,
    text: String,
    columns: Int,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    focusOnPlacement: Boolean,
    enabled: Boolean,
    allowEmpty: Boolean,
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

@Composable
public actual fun InteractionText(value: String, columns: Int, textStyle: TextStyle) {
    value.wrapToTerminalWidth(columns.coerceAtLeast(1)).forEach { line ->
        Text(value = line, textStyle = textStyle)
    }
}
