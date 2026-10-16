package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun Path.isRegularFileForShellResolution(): Boolean =
    runCatching { SystemFileSystem.metadataOrNull(this)?.isRegularFile == true }
        .getOrDefault(false)
