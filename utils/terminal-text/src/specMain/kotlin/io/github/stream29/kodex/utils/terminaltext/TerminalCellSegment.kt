package io.github.stream29.kodex.utils.terminaltext

/** One grapheme-preserving terminal-cell segment with UTF-16 source offsets. */
public data class TerminalCellSegment(
    public val sourceStart: Int,
    public val sourceEnd: Int,
    public val cellWidth: Int,
)
