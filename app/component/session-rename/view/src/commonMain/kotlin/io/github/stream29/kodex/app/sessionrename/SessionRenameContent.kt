package io.github.stream29.kodex.app.sessionrename

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiEditor
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
public actual fun SessionRenameContent(
    viewModel: SessionRenameViewModel,
    onSubmitted: () -> Unit,
    presentation: SessionRenamePresentation,
    frame: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    val draft by viewModel.draftName.collectAsState()
    val scope = rememberCoroutineScope()
    val currentOnSubmitted by rememberUpdatedState(onSubmitted)
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    frame {
        UiColumn {
            UiText("Rename session", UiTextRole.Title)
            key(viewModel) {
                var cursor by remember { mutableStateOf(draft.length) }
                UiEditor(
                    text = draft,
                    cursorOffset = cursor.coerceIn(0, draft.length),
                    label = if (presentation == SessionRenamePresentation.Labeled) "Session name" else "",
                    autoFocus = true,
                    multiline = false,
                    onChange = { text, offset ->
                        cursor = offset
                        viewModel.updateDraftName(text)
                        null
                    },
                    onSubmit = { _, _ ->
                        if (viewModel.draftName.value.isNotBlank()) {
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                viewModel.rename()
                                if (viewModel.isActive) currentOnSubmitted()
                            }
                        }
                    },
                )
            }
        }
    }
}
