package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerPopup
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * One owning component over a borrowed browser renderer. Selection starts from
 * the browser's confirmed output; Settings handoff can finish synchronously,
 * whereas an Application operation may suspend. Only a still-present instance
 * reports normal completion to its own host.
 */
@Composable
public fun BoxScope.WorkingDirectoryPopup(
    viewModel: WorkingDirectoryViewModel,
    onDismissRequest: () -> Unit,
    onSelected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifetime = remember(viewModel) { WorkingDirectoryRendererLifetime() }
    val currentOnSelected by rememberUpdatedState(onSelected)
    DisposableEffect(viewModel) {
        onDispose {
            lifetime.present = false
            viewModel.close()
        }
    }
    DirectoryPickerPopup(
        viewModel = viewModel.picker,
        onDismissRequest = onDismissRequest,
        closeOnDispose = false,
        onDirectorySelected = { directory ->
            if (lifetime.present && viewModel.isActive) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.select(directory)
                    if (lifetime.present) currentOnSelected()
                }
            }
        },
    )
}

private class WorkingDirectoryRendererLifetime {
    var present: Boolean = true
}
