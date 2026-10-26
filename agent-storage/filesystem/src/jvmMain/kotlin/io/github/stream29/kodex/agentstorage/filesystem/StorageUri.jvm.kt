package io.github.stream29.kodex.agentstorage.filesystem

import kotlinx.io.files.Path

internal actual fun Path.toStorageUri(): String =
    if (java.io.File.separatorChar == '\\') {
        formatWindowsStorageUri(toString())
    } else {
        "file://$this"
    }
