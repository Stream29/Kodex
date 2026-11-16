package io.github.stream29.kodex.utils.applypatch

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
