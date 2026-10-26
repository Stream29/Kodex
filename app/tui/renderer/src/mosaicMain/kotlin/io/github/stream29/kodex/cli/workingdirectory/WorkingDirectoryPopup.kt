package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.pathpicker.MosaicDirectoryPickerRenderer

@Composable
public actual fun BoxScope.WorkingDirectoryPopup(
    viewModel: WorkingDirectoryViewModel, onDismissRequest: () -> Unit, onSelected: () -> Unit,
) {
    WorkingDirectoryContent(viewModel, remember(this) { MosaicDirectoryPickerRenderer(this) }, onDismissRequest, onSelected)
}
