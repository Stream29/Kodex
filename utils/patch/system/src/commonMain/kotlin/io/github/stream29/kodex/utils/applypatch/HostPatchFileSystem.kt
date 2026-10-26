package io.github.stream29.kodex.utils.applypatch

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public actual suspend fun Patch.applyToFileSystem(root: Path): PatchApplyResult =
    applyToFileSystem(root, SystemCoroutineFileSystem)
