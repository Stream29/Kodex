package io.github.stream29.kodex.app.workingdirectory

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModelFactory
import kotlinx.io.files.Path

public object DefaultWorkingDirectoryViewModelFactory : WorkingDirectoryViewModelFactory {
    override fun create(
        picker: DirectoryPickerViewModel,
        dependencies: WorkingDirectoryDependencies,
    ): WorkingDirectoryViewModel = DefaultWorkingDirectoryViewModel(picker, dependencies)
}

public fun createWorkingDirectoryViewModel(
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
