package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.io.files.Path
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.GetFileAttributesW
import platform.windows.INVALID_FILE_ATTRIBUTES

internal actual fun Path.isRegularFileForShellResolution(): Boolean {
    val attributes = GetFileAttributesW(windowsPath())
    return attributes != INVALID_FILE_ATTRIBUTES &&
        attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() == 0u
}
