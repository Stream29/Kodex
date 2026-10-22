package io.github.stream29.kodex.utils.terminaltext

import io.github.kotlinmania.unicodesegmentation.graphemeIndices
import io.github.kotlinmania.unicodewidth.unicodeWidth

private data class TerminalTextWidthBoundary(
    val sourceIndex: Int,
    val cellWidth: Int,
)

public actual fun String.terminalCellSegments(): List<TerminalCellSegment> =
    graphemeIndices(isExtended = true).map { cluster ->
        TerminalCellSegment(
            sourceStart = cluster.index,
            sourceEnd = cluster.index + cluster.value.length,
            cellWidth = cluster.value.unicodeWidth(),
        )
    }.toList()

public actual fun String.terminalCellWidth(): Int =
    graphemeIndices(isExtended = true).sumOf { it.value.unicodeWidth() }

public actual fun String.takeFirstFittingTerminalWidth(maximumWidth: Int): String {
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

public actual fun String.takeLastFittingTerminalWidth(maximumWidth: Int): String {
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
