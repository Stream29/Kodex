package io.github.stream29.kodex.cli.workingdirectory

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerRenderer

/** Owns this short-lived selection child; the nested Picker is borrowed and is not independently closed. */
@Composable
public expect fun WorkingDirectoryContent(
    viewModel: WorkingDirectoryViewModel,
    renderer: DirectoryPickerRenderer,
    onDismissRequest: () -> Unit,
    onSelected: () -> Unit = {},
): Unit
