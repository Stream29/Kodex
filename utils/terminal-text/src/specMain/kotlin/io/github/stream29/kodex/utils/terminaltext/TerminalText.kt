package io.github.stream29.kodex.utils.terminaltext

/** Returns grapheme-preserving terminal-cell segments with offsets into this string. */
public expect fun String.terminalCellSegments(): List<TerminalCellSegment>

/** Returns the number of terminal cells occupied by this text. */
public expect fun String.terminalCellWidth(): Int

/** Returns the longest grapheme-preserving prefix within [maximumWidth] terminal cells. */
public expect fun String.takeFirstFittingTerminalWidth(maximumWidth: Int): String

/** Returns the longest grapheme-preserving suffix within [maximumWidth] terminal cells. */
public expect fun String.takeLastFittingTerminalWidth(maximumWidth: Int): String
