package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import io.github.stream29.kodex.cli.settings.SubmitKey

public actual val LocalTextInputFocusRequester: ProvidableCompositionLocal<FocusRequester?> =
    staticCompositionLocalOf { null }

@Composable
public actual fun ActionTextInput(
    state: TextInputState,
    layout: TextInputLayout,
    submitKey: SubmitKey,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)?,
    enabled: Boolean,
    readOnly: Boolean,
    autoFocus: Boolean,
    multiline: Boolean,
    focusRequester: FocusRequester?,
) {
    TextInput(
        state = state,
        layout = layout,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        onValueChanged = onValueChanged,
        onKeyEvent = { event ->
            when {
                event.key != "Enter" || event.alt -> false
                !event.shift && event.ctrl == (submitKey == SubmitKey.CtrlEnter) -> {
                    onSubmit()
                    true
                }
                !event.ctrl && event.shift == (submitKey == SubmitKey.Enter) -> {
                    if (multiline && state.edit(TextInputEdit.Insert("\n"))) onValueChanged?.invoke(state.value)
                    true
                }
                else -> false
            }
        },
    )
}
