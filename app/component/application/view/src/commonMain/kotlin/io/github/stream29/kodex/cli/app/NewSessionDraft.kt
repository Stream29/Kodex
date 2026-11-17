package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.ui.UiEditor
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun NewSessionDraft(
    composerViewModel: ComposerViewModel,
    newLineKey: NewLineKey,
    onSubmit: (expectedRevision: Long) -> Unit,
    maximumEditorLines: Int,
    frame: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    val state by composerViewModel.state.collectAsState()
    frame {
        key(composerViewModel, state.ownerId) {
            UiEditor(
                state.text, state.cursorOffset,
                revision = state.revision,
                onChange = { text, cursor -> composerViewModel.update(text, cursor) },
                onSubmit = { text, cursor ->
                    val revision = composerViewModel.update(text, cursor)
                    onSubmit(revision)
                },
                autoFocus = true,
                maximumLines = maximumEditorLines,
                submitKey = newLineKey.submitKey,
            )
        }
    }
}
