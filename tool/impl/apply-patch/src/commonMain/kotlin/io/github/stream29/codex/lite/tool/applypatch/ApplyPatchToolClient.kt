package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.utils.applypatch.applyToFileSystem
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class ApplyPatchToolClient(
    private val root: Path = Path("."),
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    public suspend fun apply(patch: String) {
        patch.parsePatch().applyToFileSystem(root, fileSystem)
    }
}
