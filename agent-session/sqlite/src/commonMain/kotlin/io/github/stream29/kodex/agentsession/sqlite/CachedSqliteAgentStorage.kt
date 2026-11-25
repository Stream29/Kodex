package io.github.stream29.kodex.agentsession.sqlite

import kotlinx.io.files.Path

internal fun Path.toStorageUri(): String {
    val path = toString()
    if (
        path.startsWith("""\\""") ||
        (path.length >= 3 && path[1] == ':' && path[2] in charArrayOf('\\', '/'))
    ) {
        val normalized = path.removePrefix("""\\?\""").replace('\\', '/')
        return if (normalized.startsWith("//")) "file:$normalized" else "file:///$normalized"
    }
    return "file://$path"
}
