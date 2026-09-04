package io.github.stream29.kodex.agentstorage.filesystem

import kotlinx.io.files.Path

internal expect fun Path.toStorageUri(): String

internal fun formatWindowsStorageUri(path: String): String {
    val normalized = path
        .removePrefix("""\\?\""")
        .replace('\\', '/')
    return when {
        normalized.startsWith("//") -> "file:$normalized"
        normalized.length >= 2 && normalized[1] == ':' -> "file:///$normalized"
        else -> "file:///$normalized"
    }
}
