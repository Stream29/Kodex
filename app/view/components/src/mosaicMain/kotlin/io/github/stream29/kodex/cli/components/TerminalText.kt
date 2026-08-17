package io.github.stream29.kodex.cli.components

import io.github.stream29.kodex.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellSegments
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

/** Wraps hard lines to terminal-cell [width] without splitting a grapheme cluster. */
public fun String.wrapToTerminalWidth(width: Int): List<String> {
    if (isEmpty()) return listOf("")

    val wrappedLines = mutableListOf<String>()
    lineSequence().forEach { line ->
        line.appendWrappedToTerminalWidth(width, wrappedLines)
    }
    return wrappedLines
}

private fun String.appendWrappedToTerminalWidth(
    width: Int,
    destination: MutableList<String>,
) {
    if (isEmpty()) {
        destination += ""
        return
    }
    if (all { character -> character in ' '..'~' }) {
        val chunkSize = width.coerceAtLeast(1)
        var start = 0
        while (start < length) {
            val end = start + chunkSize.coerceAtMost(length - start)
            destination += substring(start, end)
            start = end
        }
        return
    }

    val segments = terminalCellSegments()
    if (width <= 0) {
        segments.forEach { segment ->
            destination += substring(segment.sourceStart, segment.sourceEnd)
        }
        return
    }

    var lineStart = 0
    var lineWidth = 0
    segments.forEach { segment ->
        val hasLineContent = segment.sourceStart > lineStart
        if (hasLineContent && lineWidth + segment.cellWidth > width) {
            destination += substring(lineStart, segment.sourceStart)
            lineStart = segment.sourceStart
            lineWidth = 0
        }

        if (segment.sourceStart == lineStart && segment.cellWidth > width) {
            destination += substring(segment.sourceStart, segment.sourceEnd)
            lineStart = segment.sourceEnd
            lineWidth = 0
        } else {
            lineWidth += segment.cellWidth
        }
    }
    if (lineStart < length) {
        destination += substring(lineStart)
    }
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
