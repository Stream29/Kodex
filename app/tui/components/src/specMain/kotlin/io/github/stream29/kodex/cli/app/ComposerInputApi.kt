package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Native Composer input widget, shared by persisted-Agent and New Session renderers.
 *
 * [state] is only the widget's buffer/selection/undo state. [onValueChanged] publishes accepted
 * edits to the real Composer child; [onSubmit] is the captured owner's intent. This primitive
 * does not allocate a ViewModel, submit/persist a message, or own the borrowed child lifecycle.
 * Newline edits remain one undo transaction and configured submit keys never insert a newline.
 * [readOnly] blocks edits and submission without removing the input's focus target.
 */
@Composable
public expect fun ComposerInput(
    state: TextInputState,
    layout: TextInputLayout,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    submitHint: String? = null,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    readOnly: Boolean = false,
): Unit
