package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

public actual fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel = createDirectoryPickerViewModel(
    initialDirectory,
    object : DirectoryPickerDependencies {
        override val browser: DirectoryPickerBrowser = SystemDirectoryPickerBrowser()
    },
    ownerScope,
)
