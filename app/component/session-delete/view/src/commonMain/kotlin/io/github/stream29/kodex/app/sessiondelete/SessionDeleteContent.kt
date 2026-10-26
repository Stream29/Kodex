package io.github.stream29.kodex.app.sessiondelete

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActionRole
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import kotlinx.coroutines.launch

@Composable
public actual fun SessionDeleteContent(
    viewModel: SessionDeleteViewModel,
    onCancel: () -> Unit,
    onResult: (Boolean) -> Unit,
    frame: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val title = viewModel.threadName ?: "Session ${viewModel.sessionIndex}"
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    frame {
        UiColumn {
            UiText("Delete $title?", UiTextRole.Title)
            UiText("This removes the persisted session.", UiTextRole.Supporting)
            UiActions {
                UiAction("Cancel", onClick = onCancel, autoFocus = true)
                UiAction("Delete", role = UiActionRole.Destructive, onClick = {
                    scope.launch {
                        val result = viewModel.delete()
                        if (viewModel.isActive) currentOnResult(result)
                    }
                })
            }
        }
    }
}
