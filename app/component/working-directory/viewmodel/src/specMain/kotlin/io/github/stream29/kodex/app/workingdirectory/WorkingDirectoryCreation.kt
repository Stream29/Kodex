@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.workingdirectory

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModelFactory

public expect object DefaultWorkingDirectoryViewModelFactory : WorkingDirectoryViewModelFactory {
    override fun create(
        picker: DirectoryPickerViewModel,
        dependencies: WorkingDirectoryDependencies,
    ): WorkingDirectoryViewModel
}

public expect fun createWorkingDirectoryViewModel(
    picker: DirectoryPickerViewModel,
    dependencies: WorkingDirectoryDependencies,
): WorkingDirectoryViewModel
