package io.github.stream29.kodex.agentstorage.filesystem

import kotlinx.io.files.Path

internal actual fun Path.toStorageUri(): String {
    val path = toString()
    return if (
        path.startsWith("""\\""") ||
        (path.length >= 3 && path[1] == ':' && path[2] in charArrayOf('\\', '/'))
    ) {
        formatWindowsStorageUri(path)
    } else {
        "file://$path"
    }
}
