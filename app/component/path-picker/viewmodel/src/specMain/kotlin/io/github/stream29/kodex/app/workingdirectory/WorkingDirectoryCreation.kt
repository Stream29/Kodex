package io.github.stream29.kodex.app.workingdirectory

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel

/** Transfers ownership of the supplied browser without initiating a cwd update. */
public expect fun createWorkingDirectoryViewModel(
    picker: DirectoryPickerViewModel,
    dependencies: WorkingDirectoryDependencies,
): WorkingDirectoryViewModel
