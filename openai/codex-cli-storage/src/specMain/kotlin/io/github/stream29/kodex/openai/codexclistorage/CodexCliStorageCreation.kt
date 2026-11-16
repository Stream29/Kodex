package io.github.stream29.kodex.openai.codexclistorage

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

/** Creates a read-only Codex CLI storage view over [directory]. */
public expect fun CodexCliStorage(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CodexCliStorage
