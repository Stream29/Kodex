@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.stream29.kodex.utils.sqlite

import kotlinx.io.files.Path

internal actual fun deleteSqliteTestFiles(path: Path) {
    val raw = path.toString()
    listOf(raw, "$raw-wal", "$raw-shm").forEach { platform.posix.remove(it) }
}
