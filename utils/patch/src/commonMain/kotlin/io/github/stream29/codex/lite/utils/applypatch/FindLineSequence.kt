package io.github.stream29.codex.lite.utils.applypatch

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
public fun findLineSequence(
    lines: List<String>,
    targetLines: List<String>,
    startIndex: Int,
    anchorAtEnd: Boolean,
): Int? {
    if (targetLines.isEmpty()) {
        return startIndex
    }
    if (targetLines.size > lines.size) {
        return null
    }

    val lastStartIndex = lines.size - targetLines.size
    val effectiveStartIndex = if (anchorAtEnd) lastStartIndex else startIndex
    if (effectiveStartIndex > lastStartIndex) {
        return null
    }

    // Keep the Rust matching order, but use KMP for each tolerance level so a
    // large file with a long patch chunk does not degenerate into repeated
    // candidate-by-candidate rescans.
    findLineSequenceWithNormalizer(lines, targetLines, effectiveStartIndex, lastStartIndex) { it }
        ?.let { return it }
    findLineSequenceWithNormalizer(lines, targetLines, effectiveStartIndex, lastStartIndex) { it.trimEnd() }
        ?.let { return it }
    findLineSequenceWithNormalizer(lines, targetLines, effectiveStartIndex, lastStartIndex) { it.trim() }
        ?.let { return it }
    findLineSequenceWithNormalizer(lines, targetLines, effectiveStartIndex, lastStartIndex) { it.normalizedPunctuation() }
        ?.let { return it }
    return null
}

private fun findLineSequenceWithNormalizer(
    lines: List<String>,
    targetLines: List<String>,
    startIndex: Int,
    lastStartIndex: Int,
    normalize: (String) -> String,
): Int? {
    val normalizedTargetLines = targetLines.map(normalize)
    val prefixTable = buildPrefixTable(normalizedTargetLines)
    val endExclusive = lastStartIndex + targetLines.size
    var targetIndex = 0

    for (lineIndex in startIndex until endExclusive) {
        val normalizedLine = normalize(lines[lineIndex])
        while (targetIndex > 0 && normalizedLine != normalizedTargetLines[targetIndex]) {
            targetIndex = prefixTable[targetIndex - 1]
        }
        if (normalizedLine == normalizedTargetLines[targetIndex]) {
            targetIndex++
            if (targetIndex == normalizedTargetLines.size) {
                return lineIndex - normalizedTargetLines.lastIndex
            }
        }
    }
    return null
}

private fun buildPrefixTable(targetLines: List<String>): IntArray {
    val prefixTable = IntArray(targetLines.size)
    var prefixLength = 0
    for (index in 1 until targetLines.size) {
        while (prefixLength > 0 && targetLines[index] != targetLines[prefixLength]) {
            prefixLength = prefixTable[prefixLength - 1]
        }
        if (targetLines[index] == targetLines[prefixLength]) {
            prefixLength++
            prefixTable[index] = prefixLength
        }
    }
    return prefixTable
}

private fun String.normalizedPunctuation(): String =
    buildString(length) {
        this@normalizedPunctuation.forEach { ch ->
            append(
                when (ch) {
                    '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                    '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                    '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                    else -> ch
                },
            )
        }
    }.trim()
