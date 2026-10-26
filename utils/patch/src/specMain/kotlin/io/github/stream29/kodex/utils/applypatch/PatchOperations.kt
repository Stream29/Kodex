package io.github.stream29.kodex.utils.applypatch

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Finds the first position where [targetLines] appear contiguously inside [lines].
 *
 * This is used while applying an update hunk: [targetLines] are the old lines
 * from the patch chunk, and the returned index is the file position that should
 * be replaced. The matcher intentionally follows Codex's Rust apply_patch
 * behavior by trying increasingly tolerant comparisons:
 *
 * - exact line equality
 * - equality after trimming trailing whitespace
 * - equality after trimming both sides
 * - equality after normalizing common Unicode punctuation
 *
 * @param startIndex The first file line where searching may begin. Later chunks
 * should search after earlier chunks to preserve patch order.
 * @param anchorAtEnd When true, search only at the last possible position. This
 * is used for chunks marked by `*** End of File`.
 */
public expect fun findLineSequence(
    lines: List<String>,
    targetLines: List<String>,
    startIndex: Int,
    anchorAtEnd: Boolean,
): Int?

/**
 * Parses this apply-patch document into its normalized wire text and semantic
 * hunks.
 *
 * Parsing is pure: it does not inspect or modify a filesystem. The normalized
 * patch text and parsed hunks are retained together so durable events can
 * preserve the original patch meaning while consumers operate on structured
 * data.
 *
 * @throws ApplyPatchException if the document has invalid boundaries,
 * malformed hunk headers, invalid line prefixes, duplicate environment ids,
 * or an empty update hunk.
 */
public expect fun String.parsePatch(): Patch

/**
 * Applies this parsed patch in hunk order under [root], using [fileSystem].
 *
 * Absolute paths are preserved; relative paths are resolved against [root].
 * Successful results list added, modified and deleted paths in operation order.
 * This operation is not transactional: earlier hunks can remain applied when a
 * later hunk fails. The supplied filesystem is borrowed, never closed.
 *
 * @throws ApplyPatchException if no files are modified, a required source is
 * absent/not regular, or a hunk cannot be matched.
 * @throws kotlinx.io.IOException if reading or publishing filesystem content fails.
 * @throws kotlinx.coroutines.CancellationException if the calling operation is cancelled.
 */
public expect suspend fun Patch.applyToFileSystem(
    root: Path = Path("."),
    fileSystem: CoroutineFileSystem,
): PatchApplyResult
