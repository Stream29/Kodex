package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/** Borrowed frontend capabilities; a browser host must not impersonate Native filesystem or shell access. */
public interface FrontendHost {
    /** Resolves in the selected execution environment, not implicitly in the browser's device. */
    public suspend fun resolveWorkingDirectory(directory: Path): Path
    public fun createDirectoryPicker(directory: Path, owner: CoroutineScope): DirectoryPickerViewModel
    /**
     * Consumes live frontend error observations for this Application's lifetime.
     * Native executes its configured local Hooks; other hosts must explicitly select their
     * supported diagnostics policy, never execute those commands on the backend instead.
     */
    public suspend fun collectFrontendErrors(
        hooks: () -> List<NotificationHook>,
        workingDirectory: Path,
        errors: Flow<String?>,
    )
}
