package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerContent
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerRenderer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
public actual fun WorkingDirectoryContent(
    viewModel: WorkingDirectoryViewModel,
    renderer: DirectoryPickerRenderer,
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
    DirectoryPickerContent(
        viewModel = viewModel.picker,
        renderer = renderer,
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
