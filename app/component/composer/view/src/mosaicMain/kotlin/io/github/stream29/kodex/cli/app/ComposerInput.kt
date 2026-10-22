package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputEdit
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey

@Composable
public actual fun ComposerInput(
    state: TextInputState,
    layout: TextInputLayout,
    newLineKey: NewLineKey,
    autoFocus: Boolean,
    enabled: Boolean,
    submitHint: String?,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)?,
    focusRequester: FocusRequester?,
    readOnly: Boolean,
) {
    Column {
        TextInput(
            state = state,
            layout = layout,
            modifier = Modifier.fillMaxWidth(),
            autoFocus = autoFocus,
            enabled = enabled,
            readOnly = readOnly,
            focusRequester = focusRequester,
            onValueChanged = onValueChanged,
            onKeyEvent = { event ->
                when {
                    newLineKey.matches(event) -> {
                        if (state.edit(TextInputEdit.Insert("\n"))) {
                            onValueChanged?.invoke(state.value)
                        }
                        true
                    }
                    newLineKey.submitKey.matches(event) -> {
                        onSubmit()
                        true
                    }
                    else -> false
                }
            },
        )
        submitHint?.let { hint ->
            Text(value = hint, textStyle = TuiTheme.typography.supporting)
        }
    }
}

private fun NewLineKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    NewLineKey.ShiftEnter -> event.shift && !event.ctrl && !event.alt
    NewLineKey.Enter -> !event.shift && !event.ctrl && !event.alt
}

private fun SubmitKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    SubmitKey.Enter -> !event.shift && !event.ctrl && !event.alt
    SubmitKey.CtrlEnter -> event.ctrl && !event.shift && !event.alt
}
