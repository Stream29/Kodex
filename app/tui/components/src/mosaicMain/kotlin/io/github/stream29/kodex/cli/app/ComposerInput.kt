package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.ActionTextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.NewLineKey

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
        ActionTextInput(
            state = state,
            layout = layout,
            autoFocus = autoFocus,
            enabled = enabled,
            readOnly = readOnly,
            focusRequester = focusRequester,
            onValueChanged = onValueChanged,
            submitKey = newLineKey.submitKey,
            onSubmit = onSubmit,
        )
        submitHint?.let { hint ->
            Text(value = hint, textStyle = TuiTheme.typography.supporting)
        }
    }
}
