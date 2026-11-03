package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public actual val NativeFrontendHost: FrontendHost = NativeFrontendHostCapabilities

private object NativeFrontendHostCapabilities : FrontendHost {
    override suspend fun resolveWorkingDirectory(directory: Path): Path = SystemCoroutineFileSystem.resolve(directory)
}
