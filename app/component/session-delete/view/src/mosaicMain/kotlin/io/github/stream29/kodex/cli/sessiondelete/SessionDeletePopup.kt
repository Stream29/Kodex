package io.github.stream29.kodex.cli.sessiondelete

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import kotlinx.coroutines.launch

/**
 * Captured-target confirmation. Reports true and false unchanged to [onResult];
 * only the host decides whether to dismiss. Failure/cancellation does not invoke
 * that callback. Cancel performs no operation. Disposal closes only the child.
 */
@Composable
public fun BoxScope.SessionDeletePopup(
    viewModel: SessionDeleteViewModel,
    onDismissRequest: () -> Unit,
    onResult: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val title = viewModel.threadName ?: "Session ${viewModel.sessionIndex}"
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(52).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Text("Delete $title?", textStyle = TuiTheme.typography.title)
            Text("This removes the persisted session.", textStyle = TuiTheme.typography.supporting)
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) {
                TuiButton(label = "Cancel", autoFocus = true, onClick = onDismissRequest)
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = {
                        scope.launch {
                            val result = viewModel.delete()
                            if (viewModel.isActive) currentOnResult(result)
                        }
                    },
                )
            }
        }
    }
}
