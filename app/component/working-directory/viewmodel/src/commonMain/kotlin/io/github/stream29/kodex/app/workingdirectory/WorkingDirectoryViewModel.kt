@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.workingdirectory

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModelFactory
import kotlinx.io.files.Path

public actual object DefaultWorkingDirectoryViewModelFactory : WorkingDirectoryViewModelFactory {
    actual override fun create(
        picker: DirectoryPickerViewModel,
        dependencies: WorkingDirectoryDependencies,
    ): WorkingDirectoryViewModel = DefaultWorkingDirectoryViewModel(picker, dependencies)
}

public actual fun createWorkingDirectoryViewModel(
    picker: DirectoryPickerViewModel,
    dependencies: WorkingDirectoryDependencies,
): WorkingDirectoryViewModel = DefaultWorkingDirectoryViewModelFactory.create(picker, dependencies)

private class DefaultWorkingDirectoryViewModel(
    override val picker: DirectoryPickerViewModel,
    private val dependencies: WorkingDirectoryDependencies,
) : WorkingDirectoryViewModel {
    override var isActive: Boolean = true
        private set

    override suspend fun select(directory: Path) {
        check(isActive) { "Working-directory popup is closed." }
        dependencies.select(directory)
        close()
    }

    override fun close() {
        if (!isActive) return
        isActive = false
        picker.close()
    }
}
