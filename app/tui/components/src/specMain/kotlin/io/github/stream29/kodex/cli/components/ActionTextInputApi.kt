package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import com.jakewharton.mosaic.focus.FocusRequester
import io.github.stream29.kodex.cli.settings.SubmitKey

/** Borrowed Native focus handle, scoped by the host of an otherwise portable editor. */
public expect val LocalTextInputFocusRequester: ProvidableCompositionLocal<FocusRequester?>

/** Native input policy. Newlines are undoable edits; read-only retains navigation and focus. */
@Composable
public expect fun ActionTextInput(
    state: TextInputState,
    layout: TextInputLayout,
    submitKey: SubmitKey,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    multiline: Boolean = true,
    focusRequester: FocusRequester? = null,
): Unit
