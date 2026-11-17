package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/**
 * Constructs one independently disposable directory-picker component using only its declared
 * dependency ports, publishing an initial loading state for [initialDirectory] and starting its
 * load in a child of [ownerScope].
 */
public expect fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    dependencies: DirectoryPickerDependencies,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel
