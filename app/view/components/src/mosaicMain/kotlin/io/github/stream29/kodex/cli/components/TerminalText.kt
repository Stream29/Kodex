package io.github.stream29.kodex.cli.components

import io.github.stream29.kodex.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellSegments
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

/** Wraps hard lines to terminal-cell [width] without splitting a Unicode scalar. */
public fun String.wrapToTerminalWidth(width: Int): List<String> {
    if (isEmpty()) return listOf("")
    return lineSequence().flatMap { line ->
        if (line.isEmpty()) return@flatMap sequenceOf("")
        sequence {
            var remaining = line
            while (remaining.isNotEmpty()) {
                val fitting = remaining.takeFirstFittingTerminalWidth(width)
                val next = fitting.ifEmpty {
                    val firstSegment = remaining.terminalCellSegments().first()
                    remaining.substring(0, firstSegment.sourceEnd)
                }
                yield(next)
                remaining = remaining.removePrefix(next)
            }
        }
    }.toList()
}

/** Truncates this text to [maximumWidth] terminal cells, appending an ellipsis when possible. */
public fun String.ellipsizeToTerminalWidth(maximumWidth: Int): String {
    if (terminalCellWidth() <= maximumWidth) return this
    val suffix = "..."
    return if (maximumWidth <= suffix.terminalCellWidth()) {
        takeFirstFittingTerminalWidth(maximumWidth)
    } else {
        takeFirstFittingTerminalWidth(maximumWidth - suffix.terminalCellWidth()) + suffix
    }
}
