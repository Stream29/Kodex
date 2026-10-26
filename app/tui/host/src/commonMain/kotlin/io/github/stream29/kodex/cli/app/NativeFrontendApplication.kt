package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path

public actual val NativeFrontendHost: FrontendHost = NativeFrontendHostCapabilities

private object NativeFrontendHostCapabilities : FrontendHost {
    override suspend fun resolveWorkingDirectory(directory: Path): Path = SystemCoroutineFileSystem.resolve(directory)
    override fun createDirectoryPicker(directory: Path, owner: CoroutineScope): DirectoryPickerViewModel =
        createDirectoryPickerViewModel(directory, owner)

    override suspend fun collectFrontendErrors(
        hooks: () -> List<NotificationHook>,
        workingDirectory: Path,
        errors: Flow<String?>,
    ) {
        collectNotificationHooks(emptyFlow(), hooks, workingDirectory, errors)
    }
}
