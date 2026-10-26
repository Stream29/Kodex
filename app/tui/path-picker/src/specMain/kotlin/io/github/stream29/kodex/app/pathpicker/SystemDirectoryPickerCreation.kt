package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/** Creates one short-lived picker ViewModel backed by the system filesystem. */
public expect fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel
