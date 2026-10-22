@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.applypatch

import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.PatchApplyResult
import io.github.stream29.kodex.utils.applypatch.applyToFileSystem
import io.github.stream29.kodex.utils.applypatch.parsePatch
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

public actual class ApplyPatchToolClient actual constructor(
    private val workingDirectoryProvider: suspend () -> Path,
    private val fileSystem: CoroutineFileSystem,
) {
    public actual suspend fun apply(patch: String): PatchApplyResult =
        apply(patch.parsePatch())

    public actual suspend fun apply(patch: Patch): PatchApplyResult =
        patch.applyToFileSystem(workingDirectoryProvider(), fileSystem)
}
