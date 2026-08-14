package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.focusCursor
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.PointerEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPasteEvent
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellSegments
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

/** Rendered rows, source mappings, cursor position, and optional viewport for a [TextInput]. */
@Immutable
public class TextInputLayout private constructor(
    private val visualLines: List<VisualLine>,
    private val selectionStart: Int,
    private val selectionEnd: Int,
    private val maximumVisibleRows: Int,
    public val width: Int,
    public val cursorColumn: Int,
    public val cursorRow: Int,
    public val cursorContentColumn: Int,
) {
    /** Display text before applying a bounded vertical viewport. */
    public val renderedText: String
        get() = lines.joinToString("\n")

    public val lines: List<String> = visualLines.map { it.prefix + it.content }

    public val rowCount: Int
        get() = visualLines.size

    public val visibleRowCount: Int
        get() = minOf(rowCount, maximumVisibleRows).coerceAtLeast(1)

    /** Returns a copy whose renderer exposes at most [maximumRows] visual rows. */
    public fun withViewportRows(maximumRows: Int): TextInputLayout {
        require(maximumRows > 0) { "A text-input viewport must contain at least one row." }
        return TextInputLayout(
            visualLines = visualLines,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            maximumVisibleRows = maximumRows,
            width = width,
            cursorColumn = cursorColumn,
            cursorRow = cursorRow,
            cursorContentColumn = cursorContentColumn,
        )
    }

    /** Builds styled text for a clamped slice of visual rows. */
    public fun visibleText(
        firstRow: Int,
        rowCount: Int = visibleRowCount,
        selectionTextStyle: TextStyle = TextStyle.Invert,
    ): AnnotatedString {
        val safeFirst = firstRow.coerceIn(0, (this.rowCount - 1).coerceAtLeast(0))
        val safeEnd = (safeFirst + rowCount.coerceAtLeast(1)).coerceAtMost(this.rowCount)
        return buildAnnotatedString {
            visualLines.subList(safeFirst, safeEnd).forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line.prefix)
                val contentStart = length
                append(line.content)
                val selectedStart = maxOf(selectionStart, line.sourceStart)
                val selectedEnd = minOf(selectionEnd, line.sourceEnd)
                if (selectedStart < selectedEnd) {
                    addStyle(
                        style = SpanStyle(textStyle = selectionTextStyle),
                        start = contentStart + selectedStart - line.sourceStart,
                        end = contentStart + selectedEnd - line.sourceStart,
                    )
                }
            }
        }
    }

    /** Maps a visual-row terminal-cell position to the nearest editable source boundary. */
    public fun sourceOffsetAt(column: Int, row: Int): Int {
        val line = visualLines[row.coerceIn(0, visualLines.lastIndex)]
        return line.sourceOffsetAt(column)
    }

    internal fun sourceOffsetAtContentColumn(row: Int, contentColumn: Int): Int {
        val line = visualLines[row.coerceIn(0, visualLines.lastIndex)]
        if (contentColumn <= 0) return line.sourceStart
        line.segments.forEach { segment ->
            if (segment.cellEnd > contentColumn) return segment.sourceStart
            if (segment.cellEnd == contentColumn) return segment.sourceEnd
        }
        return line.sourceEnd
    }

    public companion object {
        /**
         * Creates a terminal-cell-aware text layout.
         *
         * When [softWrap] is false, each hard line retains the legacy horizontal clipping
         * behavior. Soft wrapping preserves source ranges and never inserts text newlines.
         */
        public fun create(
            value: TextInputValue,
            width: Int,
            firstLinePrefix: String = "",
            continuationLinePrefix: String = firstLinePrefix,
            softWrap: Boolean = false,
        ): TextInputLayout {
            val safeWidth = width.coerceAtLeast(1)
            val safeOffset = value.cursorOffset.coerceIn(0, value.text.length)
            val visualLines = if (softWrap) {
                softWrappedLines(
                    text = value.text,
                    width = safeWidth,
                    firstLinePrefix = firstLinePrefix,
                    continuationLinePrefix = continuationLinePrefix,
                )
            } else {
                clippedHardLines(
                    text = value.text,
                    cursorOffset = safeOffset,
                    width = safeWidth,
                    firstLinePrefix = firstLinePrefix,
                    continuationLinePrefix = continuationLinePrefix,
                )
            }
            var cursorRow = visualLines.indexOfLast { line ->
                safeOffset in line.sourceStart..line.sourceEnd
            }.coerceAtLeast(0)
            val cursorLineBeforeHardBreak = visualLines[cursorRow]
            if (
                softWrap &&
                value.text.getOrNull(safeOffset) == '\n' &&
                cursorLineBeforeHardBreak.contentWidth > 0 &&
                cursorLineBeforeHardBreak.contentWidth >= cursorLineBeforeHardBreak.availableWidth &&
                cursorRow < visualLines.lastIndex
            ) {
                cursorRow++
            }
            val cursorLine = visualLines[cursorRow]
            val cursorInLine = safeOffset.coerceIn(cursorLine.sourceStart, cursorLine.sourceEnd)
            val cursorContentColumn = value.text
                .substring(cursorLine.sourceStart, cursorInLine)
                .terminalCellWidth()
            val cursorColumn = cursorLine.prefixWidth + cursorContentColumn
            return TextInputLayout(
                visualLines = visualLines,
                selectionStart = value.selectionStart,
                selectionEnd = value.selectionEnd,
                maximumVisibleRows = Int.MAX_VALUE,
                width = safeWidth,
                cursorColumn = cursorColumn,
                cursorRow = cursorRow,
                cursorContentColumn = cursorContentColumn,
            )
        }
    }
}

/** A focusable terminal text input backed by [state]. */
@Composable
public fun TextInput(
    state: TextInputState,
    layout: TextInputLayout,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    autoFocus: Boolean = false,
    enabled: Boolean = true,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
) {
    val latestOnKeyEvent = rememberUpdatedState(onKeyEvent)
    val latestOnValueChanged = rememberUpdatedState(onValueChanged)
    val requesterModifier = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    val firstVisibleRow = state.resolvedScrollOffset(layout)
    val cursorViewportRow =
        (layout.cursorRow - firstVisibleRow).coerceIn(0, layout.visibleRowCount - 1)
    val scrollableState = rememberScrollableState(
        consumeScrollDelta = { delta -> state.scrollBy(delta, layout) },
        canScrollBackward = { state.resolvedScrollOffset(layout) > 0 },
        canScrollForward = {
            state.resolvedScrollOffset(layout) <
                (layout.rowCount - layout.visibleRowCount).coerceAtLeast(0)
        },
    )
    var pointerSelecting by remember(state) { mutableStateOf(false) }

    SideEffect {
        state.commitViewport(layout, firstVisibleRow)
    }

    fun notifyIfChanged(changed: Boolean) {
        if (changed) latestOnValueChanged.value?.invoke(state.value)
    }

    fun applyEdit(edit: TextInputEdit) {
        notifyIfChanged(state.edit(edit))
    }

    fun moveCursor(movement: TextInputMovement, extendSelection: Boolean) {
        notifyIfChanged(
            state.moveCursor(
                movement = movement,
                extendSelection = extendSelection,
                layout = layout,
            ),
        )
    }

    fun pointerOffset(event: PointerEvent): Int {
        val row = (firstVisibleRow + event.position.y).coerceIn(0, layout.rowCount - 1)
        return layout.sourceOffsetAt(column = event.position.x, row = row)
    }

    val inputModifier = if (enabled) {
        modifier
            .then(requesterModifier)
            .focusable(autoFocus = autoFocus)
            .focusCursor(layout.cursorColumn, cursorViewportRow)
            .scrollable(state = scrollableState)
            .onPasteEvent { event ->
                applyEdit(TextInputEdit.Insert(event.text.normalizeLineEndings()))
                true
            }
            .onKeyEvent { event ->
                when {
                    latestOnKeyEvent.value?.invoke(event) == true -> true
                    event.matchesControlKey("z") -> {
                        notifyIfChanged(state.undo())
                        true
                    }

                    event.matchesControlKey("y") -> {
                        notifyIfChanged(state.redo())
                        true
                    }

                    event.matchesControlKey("w") -> {
                        applyEdit(TextInputEdit.DeletePreviousWord)
                        true
                    }

                    event.key == "Backspace" && !event.ctrl && !event.alt -> {
                        applyEdit(TextInputEdit.DeleteBeforeCursor)
                        true
                    }

                    event.key == "Delete" && !event.ctrl && !event.alt -> {
                        applyEdit(TextInputEdit.DeleteAtCursor)
                        true
                    }

                    event.key == "ArrowLeft" && !event.ctrl && !event.alt -> {
                        moveCursor(TextInputMovement.Left, event.shift)
                        true
                    }

                    event.key == "ArrowRight" && !event.ctrl && !event.alt -> {
                        moveCursor(TextInputMovement.Right, event.shift)
                        true
                    }

                    event.key == "ArrowUp" && !event.ctrl && !event.alt -> {
                        moveCursor(TextInputMovement.Up, event.shift)
                        true
                    }

                    event.key == "ArrowDown" && !event.ctrl && !event.alt -> {
                        moveCursor(TextInputMovement.Down, event.shift)
                        true
                    }

                    event.key == "Home" && !event.alt -> {
                        moveCursor(
                            movement = if (event.ctrl) {
                                TextInputMovement.DocumentStart
                            } else {
                                TextInputMovement.LineStart
                            },
                            extendSelection = event.shift,
                        )
                        true
                    }

                    event.key == "End" && !event.alt -> {
                        moveCursor(
                            movement = if (event.ctrl) {
                                TextInputMovement.DocumentEnd
                            } else {
                                TextInputMovement.LineEnd
                            },
                            extendSelection = event.shift,
                        )
                        true
                    }

                    !event.ctrl && !event.alt && event.key.isSingleScalar() -> {
                        applyEdit(TextInputEdit.Insert(event.key, mergeWithPrevious = true))
                        true
                    }

                    else -> false
                }
            }
            .onPointerEvent { event ->
                when (event.type) {
                    MouseEvent.Type.Press -> {
                        if (event.button != MouseEvent.Button.Left || event.shift) {
                            return@onPointerEvent false
                        }
                        pointerSelecting = true
                        notifyIfChanged(state.beginPointerSelection(pointerOffset(event)))
                        true
                    }

                    MouseEvent.Type.Drag -> {
                        if (!pointerSelecting) return@onPointerEvent false
                        notifyIfChanged(state.extendPointerSelection(pointerOffset(event)))
                        true
                    }

                    MouseEvent.Type.Release -> {
                        if (!pointerSelecting) return@onPointerEvent false
                        notifyIfChanged(state.extendPointerSelection(pointerOffset(event)))
                        pointerSelecting = false
                        true
                    }

                    MouseEvent.Type.Motion -> false
                }
            }
    } else {
        modifier
    }
    Text(
        value = layout.visibleText(
            firstRow = firstVisibleRow,
            selectionTextStyle = if (enabled) {
                TextStyle.Invert
            } else {
                TextStyle.Dim + TextStyle.Invert
            },
        ),
        modifier = inputModifier,
        textStyle = if (enabled) TextStyle.Unspecified else TextStyle.Dim,
    )
}

@Immutable
private data class HardLine(
    val sourceStart: Int,
    val sourceEnd: Int,
)

@Immutable
private data class VisualSegment(
    val sourceStart: Int,
    val sourceEnd: Int,
    val cellStart: Int,
    val cellEnd: Int,
)

@Immutable
private data class VisualLine(
    val prefix: String,
    val content: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val segments: List<VisualSegment>,
    val contentWidth: Int,
    val availableWidth: Int,
) {
    val prefixWidth: Int
        get() = prefix.terminalCellWidth()

    fun sourceOffsetAt(column: Int): Int {
        val contentColumn = column - prefixWidth
        if (contentColumn <= 0) return sourceStart
        segments.forEach { segment ->
            if (segment.cellEnd <= contentColumn) return@forEach
            val width = segment.cellEnd - segment.cellStart
            if (width <= 0) return@forEach
            val distance = contentColumn - segment.cellStart
            return if (distance * 2 < width) segment.sourceStart else segment.sourceEnd
        }
        return sourceEnd
    }
}

private fun clippedHardLines(
    text: String,
    cursorOffset: Int,
    width: Int,
    firstLinePrefix: String,
    continuationLinePrefix: String,
): List<VisualLine> {
    val hardLines = text.hardLines()
    val cursorHardLine = hardLines.indexOfFirst { cursorOffset <= it.sourceEnd }.coerceAtLeast(0)
    return hardLines.mapIndexed { index, hardLine ->
        val prefix = if (index == 0) firstLinePrefix else continuationLinePrefix
        val available = (width - prefix.terminalCellWidth()).coerceAtLeast(0)
        val visibleStart: Int
        val visibleEnd: Int
        if (index == cursorHardLine) {
            val before = text.substring(hardLine.sourceStart, cursorOffset)
            val visibleBefore = before.takeLastFittingTerminalWidth(available)
            val visibleAfter = text.substring(cursorOffset, hardLine.sourceEnd)
                .takeFirstFittingTerminalWidth(available - visibleBefore.terminalCellWidth())
            visibleStart = cursorOffset - visibleBefore.length
            visibleEnd = cursorOffset + visibleAfter.length
        } else {
            visibleStart = hardLine.sourceStart
            visibleEnd = hardLine.sourceStart + text
                .substring(hardLine.sourceStart, hardLine.sourceEnd)
                .takeFirstFittingTerminalWidth(available)
                .length
        }
        visualLine(
            text = text,
            prefix = prefix,
            sourceStart = visibleStart,
            sourceEnd = visibleEnd,
            availableWidth = available,
        )
    }
}

private fun softWrappedLines(
    text: String,
    width: Int,
    firstLinePrefix: String,
    continuationLinePrefix: String,
): List<VisualLine> = buildList {
    val hardLines = text.hardLines()
    hardLines.forEachIndexed { hardLineIndex, hardLine ->
        if (hardLine.sourceStart == hardLine.sourceEnd) {
            val prefix = fittingPrefix(
                requested = if (isEmpty()) firstLinePrefix else continuationLinePrefix,
                width = width,
                firstContentWidth = 1,
            )
            add(
                visualLine(
                    text = text,
                    prefix = prefix,
                    sourceStart = hardLine.sourceStart,
                    sourceEnd = hardLine.sourceEnd,
                    availableWidth = (width - prefix.terminalCellWidth()).coerceAtLeast(0),
                ),
            )
            return@forEachIndexed
        }

        val hardContent = text.substring(hardLine.sourceStart, hardLine.sourceEnd)
        val segments = hardContent.terminalCellSegments()
        var segmentIndex = 0
        while (segmentIndex < segments.size) {
            val firstSegment = segments[segmentIndex]
            val prefix = fittingPrefix(
                requested = if (isEmpty()) firstLinePrefix else continuationLinePrefix,
                width = width,
                firstContentWidth = firstSegment.cellWidth,
            )
            val available = (width - prefix.terminalCellWidth()).coerceAtLeast(0)
            val rowStartIndex = segmentIndex
            var contentWidth = 0
            while (segmentIndex < segments.size) {
                val segment = segments[segmentIndex]
                val wouldOverflow = contentWidth + segment.cellWidth > available
                if (segmentIndex > rowStartIndex && wouldOverflow) break
                contentWidth += segment.cellWidth
                segmentIndex++
                if (wouldOverflow) break
            }
            val sourceStart = hardLine.sourceStart + segments[rowStartIndex].sourceStart
            val sourceEnd = hardLine.sourceStart + segments[segmentIndex - 1].sourceEnd
            add(
                visualLine(
                    text = text,
                    prefix = prefix,
                    sourceStart = sourceStart,
                    sourceEnd = sourceEnd,
                    availableWidth = available,
                ),
            )
        }

        val last = last()
        if (
            hardLineIndex == hardLines.lastIndex &&
            last.sourceStart < last.sourceEnd &&
            last.contentWidth > 0 &&
            last.contentWidth >= last.availableWidth
        ) {
            val prefix = fittingPrefix(
                requested = continuationLinePrefix,
                width = width,
                firstContentWidth = 1,
            )
            add(
                visualLine(
                    text = text,
                    prefix = prefix,
                    sourceStart = hardLine.sourceEnd,
                    sourceEnd = hardLine.sourceEnd,
                    availableWidth = (width - prefix.terminalCellWidth()).coerceAtLeast(0),
                ),
            )
        }
    }
}

private fun visualLine(
    text: String,
    prefix: String,
    sourceStart: Int,
    sourceEnd: Int,
    availableWidth: Int,
): VisualLine {
    val content = text.substring(sourceStart, sourceEnd)
    var cellOffset = 0
    val segments = content.terminalCellSegments().map { segment ->
        val start = cellOffset
        cellOffset += segment.cellWidth
        VisualSegment(
            sourceStart = sourceStart + segment.sourceStart,
            sourceEnd = sourceStart + segment.sourceEnd,
            cellStart = start,
            cellEnd = cellOffset,
        )
    }
    return VisualLine(
        prefix = prefix,
        content = content,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        segments = segments,
        contentWidth = cellOffset,
        availableWidth = availableWidth,
    )
}

private fun fittingPrefix(requested: String, width: Int, firstContentWidth: Int): String {
    var prefix = requested.takeFirstFittingTerminalWidth(width)
    val available = (width - prefix.terminalCellWidth()).coerceAtLeast(0)
    if (firstContentWidth > available) {
        val reservedContentWidth = minOf(firstContentWidth, width)
        prefix = requested.takeFirstFittingTerminalWidth(width - reservedContentWidth)
    }
    return prefix
}

private fun String.hardLines(): List<HardLine> = buildList {
    var start = 0
    this@hardLines.forEachIndexed { index, character ->
        if (character == '\n') {
            add(HardLine(sourceStart = start, sourceEnd = index))
            start = index + 1
        }
    }
    add(HardLine(sourceStart = start, sourceEnd = length))
}

private fun String.normalizeLineEndings(): String {
    if ('\r' !in this) return this
    return buildString(length) {
        var index = 0
        while (index < this@normalizeLineEndings.length) {
            val character = this@normalizeLineEndings[index]
            if (character == '\r') {
                append('\n')
                if (this@normalizeLineEndings.getOrNull(index + 1) == '\n') index++
            } else {
                append(character)
            }
            index++
        }
    }
}

private fun KeyEvent.matchesControlKey(expected: String): Boolean =
    ctrl && !alt && !shift && key.equals(expected, ignoreCase = true)

private fun String.isSingleScalar(): Boolean = length == 1 ||
    (length == 2 && first().isHighSurrogate() && last().isLowSurrogate())
