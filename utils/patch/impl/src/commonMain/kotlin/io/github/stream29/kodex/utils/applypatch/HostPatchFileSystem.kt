package io.github.stream29.kodex.utils.applypatch

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

/** Applies this patch using the actual host filesystem; see the explicit-FS operation for guarantees. */
public suspend fun Patch.applyToFileSystem(root: Path = Path(".")): PatchApplyResult =
    applyToFileSystem(root, SystemCoroutineFileSystem)
