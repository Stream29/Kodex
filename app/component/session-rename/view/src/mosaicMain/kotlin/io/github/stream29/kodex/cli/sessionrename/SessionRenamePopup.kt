package io.github.stream29.kodex.cli.sessionrename

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Renderer-only layout choices; neither changes the component's operation contract. */
public enum class SessionRenamePresentation {
    Compact,
    Labeled,
}

/**
 * Renders the spec's draft; only cursor/layout are local. [onSubmitted] is called
 * after a normal operation return, not after a persistence acknowledgment.
 * The host handles its exact popup/revision; exceptions leave the editor open.
 * Removal from composition closes the child, never its dependency.
 */
@Composable
public fun BoxScope.SessionRenamePopup(
    viewModel: SessionRenameViewModel,
    onDismissRequest: () -> Unit,
    onSubmitted: () -> Unit,
    presentation: SessionRenamePresentation = SessionRenamePresentation.Compact,
) {
    val scope = rememberCoroutineScope()
    val draft by viewModel.draftName.collectAsState()
    val currentOnSubmitted by rememberUpdatedState(onSubmitted)
    val input = remember(viewModel) { TextInputState(TextInputValue(draft, draft.length)) }
    val labeled = presentation == SessionRenamePresentation.Labeled
    val width = if (labeled) {
        (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    } else {
        48
    }
    val background = TuiTheme.colorScheme.surfaceContainer
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    LaunchedEffect(draft) {
        if (input.value.text != draft) input.reset(TextInputValue(draft, draft.length))
    }
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(width).background(background),
    ) {
        Column(modifier = if (labeled) Modifier.fillMaxWidth().background(background) else Modifier) {
            if (labeled) {
                Text(
                    "Rename session",
                    modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                    color = TuiTheme.colorScheme.onSurface,
                    textStyle = TuiTheme.typography.headline,
                )
                Text("Session name", color = TuiTheme.colorScheme.onSurface)
            }
            SessionRenameEditor(
                draftName = draft,
                input = input,
                width = if (labeled) width else width - 2,
                onDraftNameChanged = viewModel::updateDraftName,
                onSubmit = {
                    // Snapshot the draft while handling Enter. In particular,
                    // Settings' queued command must precede later input events.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        viewModel.rename()
                        if (viewModel.isActive) currentOnSubmitted()
                    }
                },
                labeled = labeled,
            )
        }
    }
}

@Composable
internal fun SessionRenameEditor(
    draftName: String,
    input: TextInputState,
    width: Int,
    onDraftNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    labeled: Boolean = false,
) {
    Column {
        if (!labeled) Text("Rename session", textStyle = TuiTheme.typography.title)
        TextInput(
            state = input,
            modifier = if (labeled) Modifier.fillMaxWidth() else Modifier,
            layout = if (labeled) TextInputLayout.create(input.value, width)
                else TextInputLayout.create(input.value, width, "> ", "  "),
            onValueChanged = { value -> onDraftNameChanged(value.text) },
            autoFocus = true,
            onKeyEvent = { event ->
                if (event.key == "Enter" && !event.shift && !event.ctrl && !event.alt &&
                    (labeled || draftName.isNotBlank())
                ) {
                    if (draftName.isNotBlank()) onSubmit()
                    true
                } else {
                    false
                }
            },
        )
    }
}
