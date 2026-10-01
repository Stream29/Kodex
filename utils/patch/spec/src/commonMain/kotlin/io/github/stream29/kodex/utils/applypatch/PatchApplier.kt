package io.github.stream29.kodex.utils.applypatch

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Applies a parsed [Patch] to a filesystem.
 *
 * The spec owns the observable operation and result. It does not prescribe
 * how paths are resolved, how file contents are matched, or which filesystem
 * implementation performs the writes.
 */
public interface PatchApplier {
    /**
     * Applies all [patch] hunks under [root].
     *
     * Hunk order is preserved. A successful result reports each added,
     * modified, and deleted path. The operation is not transactional: if a
     * later hunk fails, earlier filesystem changes remain observable.
     *
     * @throws ApplyPatchException if the patch is empty, a required source
     * file is absent or not regular, or a hunk cannot be matched.
     */
    public suspend fun apply(
        patch: Patch,
        root: Path = Path("."),
        fileSystem: CoroutineFileSystem,
    ): PatchApplyResult
}
