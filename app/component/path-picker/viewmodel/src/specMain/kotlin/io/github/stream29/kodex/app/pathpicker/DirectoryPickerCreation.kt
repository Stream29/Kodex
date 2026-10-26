@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/** Creates a picker using only the component's declared dependency ports. */
public expect fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    dependencies: DirectoryPickerDependencies,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel

public expect object DefaultDirectoryPickerViewModelFactory : DirectoryPickerViewModelFactory {
    override fun create(
        initialDirectory: Path,
        dependencies: DirectoryPickerDependencies,
        ownerScope: CoroutineScope,
    ): DirectoryPickerViewModel
}
