package io.github.stream29.codex.lite.utils.terminaltext

import io.github.kotlinmania.unicodesegmentation.graphemeIndices
import io.github.kotlinmania.unicodewidth.unicodeWidth

private data class TerminalTextWidthBoundary(
    val sourceIndex: Int,
    val cellWidth: Int,
)

/** Returns the number of terminal cells occupied by this text. */
public fun String.terminalCellWidth(): Int =
    graphemeIndices(isExtended = true).sumOf { it.value.unicodeWidth() }

/** Returns the longest grapheme-preserving prefix within [maximumWidth] terminal cells. */
public fun String.takeFirstFittingTerminalWidth(maximumWidth: Int): String {
    if (maximumWidth <= 0) return ""

    val boundary = graphemeIndices(isExtended = true)
        .runningFold(TerminalTextWidthBoundary(sourceIndex = 0, cellWidth = 0)) { prefix, cluster ->
            TerminalTextWidthBoundary(
                sourceIndex = cluster.index + cluster.value.length,
                cellWidth = prefix.cellWidth + cluster.value.unicodeWidth(),
            )
        }
        .takeWhile { it.cellWidth <= maximumWidth }
        .last()
    return substring(0, boundary.sourceIndex)
}

/** Returns the longest grapheme-preserving suffix within [maximumWidth] terminal cells. */
public fun String.takeLastFittingTerminalWidth(maximumWidth: Int): String {
    if (maximumWidth <= 0) return ""

    val totalWidth = terminalCellWidth()
    if (totalWidth <= maximumWidth) return this

    val boundary = graphemeIndices(isExtended = true)
        .runningFold(TerminalTextWidthBoundary(sourceIndex = 0, cellWidth = totalWidth)) { suffix, cluster ->
            TerminalTextWidthBoundary(
                sourceIndex = cluster.index + cluster.value.length,
                cellWidth = suffix.cellWidth - cluster.value.unicodeWidth(),
            )
        }
        .first { it.cellWidth <= maximumWidth }
    return substring(boundary.sourceIndex)
}
