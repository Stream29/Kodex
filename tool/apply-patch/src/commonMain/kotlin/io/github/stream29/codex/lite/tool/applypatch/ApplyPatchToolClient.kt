package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.utils.applypatch.Patch
import io.github.stream29.codex.lite.utils.applypatch.PatchApplyResult
import io.github.stream29.codex.lite.utils.applypatch.applyToFileSystem
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class ApplyPatchToolClient(
    private val workingDirectoryProvider: suspend () -> Path = { Path(".") },
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    public suspend fun apply(patch: String): PatchApplyResult =
        apply(patch.parsePatch())

    public suspend fun apply(patch: Patch): PatchApplyResult =
        patch.applyToFileSystem(workingDirectoryProvider(), fileSystem)
}
