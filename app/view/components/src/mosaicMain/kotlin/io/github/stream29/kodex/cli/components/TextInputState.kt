package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Immutable text, active UTF-16 cursor offset, and selection anchor. */
@Immutable
public data class TextInputValue(
    public val text: String = "",
    public val cursorOffset: Int = text.length,
    public val selectionAnchor: Int = cursorOffset,
) {
    init {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the text." }
        require(selectionAnchor in 0..text.length) { "Selection anchor must be within the text." }
    }

    public val hasSelection: Boolean
        get() = cursorOffset != selectionAnchor

    public val selectionStart: Int
        get() = minOf(cursorOffset, selectionAnchor)

    public val selectionEnd: Int
        get() = maxOf(cursorOffset, selectionAnchor)
}

/** A single text-input mutation or compatibility cursor movement. */
public sealed interface TextInputEdit {
    /**
     * Inserts [text], replacing the current selection.
     *
     * [mergeWithPrevious] is reserved for ordinary character input. Paste, newline, and other
     * caller-owned edits remain atomic by using the default value.
     */
    public data class Insert(
        public val text: String,
        public val mergeWithPrevious: Boolean = false,
    ) : TextInputEdit

    public data object DeleteBeforeCursor : TextInputEdit

    public data object DeleteAtCursor : TextInputEdit

    public data object DeletePreviousWord : TextInputEdit

    public data object MoveCursorLeft : TextInputEdit

    public data object MoveCursorRight : TextInputEdit

    public data object MoveCursorToStart : TextInputEdit

    public data object MoveCursorToEnd : TextInputEdit
}

/** Cursor movements supported by [TextInputState.moveCursor]. */
public enum class TextInputMovement {
    Left,
    Right,
    Up,
    Down,
    LineStart,
    LineEnd,
    DocumentStart,
    DocumentEnd,
}

/** Applies this edit to [value] using Unicode-scalar cursor semantics. */
public fun TextInputEdit.applyTo(value: TextInputValue): TextInputValue = when (this) {
    is TextInputEdit.Insert -> value.replaceSelection(text)
    TextInputEdit.DeleteBeforeCursor -> {
        if (value.hasSelection) {
            value.replaceSelection("")
        } else {
            val start = value.text.previousScalar(value.cursorOffset)
            if (start == value.cursorOffset) value else value.replaceRange(start, value.cursorOffset, "")
        }
    }

    TextInputEdit.DeleteAtCursor -> {
        if (value.hasSelection) {
            value.replaceSelection("")
        } else {
            val end = value.text.nextScalar(value.cursorOffset)
            if (end == value.cursorOffset) value else value.replaceRange(value.cursorOffset, end, "")
        }
    }

    TextInputEdit.DeletePreviousWord -> {
        if (value.hasSelection) {
            value.replaceSelection("")
        } else {
            val start = value.text.previousWordGroupStart(value.cursorOffset)
            if (start == value.cursorOffset) value else value.replaceRange(start, value.cursorOffset, "")
        }
    }

    TextInputEdit.MoveCursorLeft -> {
        val target = if (value.hasSelection) {
            value.selectionStart
        } else {
            value.text.previousScalar(value.cursorOffset)
        }
        value.withCollapsedCursor(target)
    }

    TextInputEdit.MoveCursorRight -> {
        val target = if (value.hasSelection) {
            value.selectionEnd
        } else {
            value.text.nextScalar(value.cursorOffset)
        }
        value.withCollapsedCursor(target)
    }

    TextInputEdit.MoveCursorToStart -> value.withCollapsedCursor(0)
    TextInputEdit.MoveCursorToEnd -> value.withCollapsedCursor(value.text.length)
}

/**
 * Mutable, composition-observable state for a terminal text input.
 *
 * Selection, preferred vertical column, bounded undo/redo history, and viewport position are local
 * editor state. Application callers continue to synchronize only text and the active cursor.
 */
@Stable
public class TextInputState(initialValue: TextInputValue = TextInputValue()) {
    public var value: TextInputValue by mutableStateOf(initialValue.withScalarBoundaries())
        private set

    public var scrollOffset: Int by mutableStateOf(0)
        private set

    private val undoHistory: MutableList<HistoryEntry> = mutableListOf()
    private val redoHistory: MutableList<HistoryEntry> = mutableListOf()
    private var activeHistoryGroup: HistoryGroup? = null
    private var preferredColumn: Int? = null
    private var followCursor: Boolean = true
    private var lastViewportWidth: Int = -1
    private var lastViewportRows: Int = -1

    public val canUndo: Boolean
        get() = undoHistory.isNotEmpty()

    public val canRedo: Boolean
        get() = redoHistory.isNotEmpty()

    /** Applies [edit] and returns whether it changed the text, cursor, or selection. */
    public fun edit(edit: TextInputEdit): Boolean {
        val before = value
        val updated = edit.applyTo(before).withScalarBoundaries()
        val movement = edit is TextInputEdit.MoveCursorLeft ||
            edit is TextInputEdit.MoveCursorRight ||
            edit is TextInputEdit.MoveCursorToStart ||
            edit is TextInputEdit.MoveCursorToEnd
        if (updated == before) {
            if (movement) breakHistoryGroup()
            return false
        }

        if (updated.text != before.text) {
            recordTextChange(before, updated, edit.historyGroup(before))
            redoHistory.clear()
        } else {
            breakHistoryGroup()
        }
        value = updated
        preferredColumn = null
        followCursor = true
        return true
    }

    /**
     * Moves the active cursor and optionally extends the current selection.
     *
     * [layout] is required for visual [TextInputMovement.Up] and [TextInputMovement.Down].
     */
    public fun moveCursor(
        movement: TextInputMovement,
        extendSelection: Boolean = false,
        layout: TextInputLayout? = null,
    ): Boolean {
        breakHistoryGroup()
        val current = value
        val vertical = movement == TextInputMovement.Up || movement == TextInputMovement.Down
        val target = when (movement) {
            TextInputMovement.Left -> if (!extendSelection && current.hasSelection) {
                current.selectionStart
            } else {
                current.text.previousScalar(current.cursorOffset)
            }

            TextInputMovement.Right -> if (!extendSelection && current.hasSelection) {
                current.selectionEnd
            } else {
                current.text.nextScalar(current.cursorOffset)
            }

            TextInputMovement.Up -> {
                val currentLayout = layout ?: return false
                val desiredColumn = preferredColumn ?: currentLayout.cursorContentColumn
                preferredColumn = desiredColumn
                if (currentLayout.cursorRow == 0) {
                    0
                } else {
                    currentLayout.sourceOffsetAtContentColumn(
                        row = currentLayout.cursorRow - 1,
                        contentColumn = desiredColumn,
                    )
                }
            }

            TextInputMovement.Down -> {
                val currentLayout = layout ?: return false
                val desiredColumn = preferredColumn ?: currentLayout.cursorContentColumn
                preferredColumn = desiredColumn
                if (currentLayout.cursorRow >= currentLayout.rowCount - 1) {
                    current.text.length
                } else {
                    currentLayout.sourceOffsetAtContentColumn(
                        row = currentLayout.cursorRow + 1,
                        contentColumn = desiredColumn,
                    )
                }
            }

            TextInputMovement.LineStart -> current.text.hardLineStart(current.cursorOffset)
            TextInputMovement.LineEnd -> current.text.hardLineEnd(current.cursorOffset)
            TextInputMovement.DocumentStart -> 0
            TextInputMovement.DocumentEnd -> current.text.length
        }.let(current.text::coerceToScalarBoundary)

        if (!vertical) preferredColumn = null
        val anchor = if (extendSelection) current.selectionAnchor else target
        val updated = current.copy(cursorOffset = target, selectionAnchor = anchor)
        followCursor = true
        if (updated == current) return false
        value = updated
        return true
    }

    /** Places the cursor from a primary-pointer press and starts a new selection anchor. */
    public fun beginPointerSelection(offset: Int): Boolean {
        breakHistoryGroup()
        preferredColumn = null
        followCursor = true
        val target = value.text.coerceToScalarBoundary(offset)
        val updated = value.copy(cursorOffset = target, selectionAnchor = target)
        if (updated == value) return false
        value = updated
        return true
    }

    /** Moves the active end of the current pointer selection. */
    public fun extendPointerSelection(offset: Int): Boolean {
        breakHistoryGroup()
        preferredColumn = null
        followCursor = true
        val target = value.text.coerceToScalarBoundary(offset)
        if (target == value.cursorOffset) return false
        value = value.copy(cursorOffset = target)
        return true
    }

    /** Restores the previous text transaction, including its cursor and selection. */
    public fun undo(): Boolean {
        val entry = undoHistory.removeLastOrNull() ?: return false
        redoHistory.add(entry)
        value = entry.before
        preferredColumn = null
        breakHistoryGroup()
        followCursor = true
        return true
    }

    /** Reapplies the next text transaction, including its cursor and selection. */
    public fun redo(): Boolean {
        val entry = redoHistory.removeLastOrNull() ?: return false
        undoHistory.add(entry)
        value = entry.after
        preferredColumn = null
        breakHistoryGroup()
        followCursor = true
        return true
    }

    /** Replaces the draft and clears all editor-local transient state. */
    public fun reset(value: TextInputValue = TextInputValue()) {
        val normalized = value.withScalarBoundaries()
        this.value = normalized.copy(selectionAnchor = normalized.cursorOffset)
        undoHistory.clear()
        redoHistory.clear()
        activeHistoryGroup = null
        preferredColumn = null
        scrollOffset = 0
        followCursor = true
        lastViewportWidth = -1
        lastViewportRows = -1
    }

    internal fun resolvedScrollOffset(layout: TextInputLayout): Int {
        val viewportRows = layout.visibleRowCount
        val maximum = (layout.rowCount - viewportRows).coerceAtLeast(0)
        var resolved = scrollOffset.coerceIn(0, maximum)
        val dimensionsChanged =
            lastViewportWidth != layout.width || lastViewportRows != viewportRows
        if (followCursor || dimensionsChanged) {
            resolved = when {
                layout.rowCount <= viewportRows -> 0
                layout.cursorRow < resolved -> layout.cursorRow
                layout.cursorRow >= resolved + viewportRows ->
                    layout.cursorRow - viewportRows + 1

                else -> resolved
            }.coerceIn(0, maximum)
        }
        return resolved
    }

    internal fun commitViewport(layout: TextInputLayout, resolvedOffset: Int) {
        val widthChanged = lastViewportWidth >= 0 && lastViewportWidth != layout.width
        if (widthChanged) preferredColumn = null
        lastViewportWidth = layout.width
        lastViewportRows = layout.visibleRowCount
        val maximum = (layout.rowCount - layout.visibleRowCount).coerceAtLeast(0)
        val clamped = resolvedOffset.coerceIn(0, maximum)
        if (scrollOffset != clamped) scrollOffset = clamped
        followCursor = false
    }

    internal fun scrollBy(delta: Int, layout: TextInputLayout): Int {
        if (delta == 0) return 0
        val current = resolvedScrollOffset(layout)
        val maximum = (layout.rowCount - layout.visibleRowCount).coerceAtLeast(0)
        val target = (current.toLong() + delta).coerceIn(0L, maximum.toLong()).toInt()
        val consumed = target - current
        if (consumed == 0) return 0
        scrollOffset = target
        lastViewportWidth = layout.width
        lastViewportRows = layout.visibleRowCount
        followCursor = false
        return consumed
    }

    private fun recordTextChange(
        before: TextInputValue,
        after: TextInputValue,
        group: HistoryGroup?,
    ) {
        val previous = undoHistory.lastOrNull()
        if (group != null && activeHistoryGroup == group && previous?.group == group) {
            undoHistory[undoHistory.lastIndex] = previous.copy(after = after)
        } else {
            undoHistory.add(HistoryEntry(before = before, after = after, group = group))
            if (undoHistory.size > MaximumHistoryEntries) undoHistory.removeAt(0)
        }
        activeHistoryGroup = group
    }

    private fun breakHistoryGroup() {
        activeHistoryGroup = null
    }
}

/** Remembers one [TextInputState] for this composition location. */
@Composable
public fun rememberTextInputState(initialValue: TextInputValue = TextInputValue()): TextInputState =
    remember { TextInputState(initialValue) }

private data class HistoryEntry(
    val before: TextInputValue,
    val after: TextInputValue,
    val group: HistoryGroup?,
)

private enum class HistoryGroup {
    Typing,
    DeleteBackward,
    DeleteForward,
}

private fun TextInputEdit.historyGroup(before: TextInputValue): HistoryGroup? = when (this) {
    is TextInputEdit.Insert -> HistoryGroup.Typing.takeIf {
        mergeWithPrevious && !before.hasSelection && text.isSingleScalar()
    }

    TextInputEdit.DeleteBeforeCursor ->
        HistoryGroup.DeleteBackward.takeUnless { before.hasSelection }

    TextInputEdit.DeleteAtCursor ->
        HistoryGroup.DeleteForward.takeUnless { before.hasSelection }

    TextInputEdit.DeletePreviousWord,
    TextInputEdit.MoveCursorLeft,
    TextInputEdit.MoveCursorRight,
    TextInputEdit.MoveCursorToStart,
    TextInputEdit.MoveCursorToEnd,
        -> null
}

private fun TextInputValue.replaceSelection(replacement: String): TextInputValue =
    replaceRange(selectionStart, selectionEnd, replacement)

private fun TextInputValue.replaceRange(start: Int, end: Int, replacement: String): TextInputValue {
    val updatedText = text.substring(0, start) + replacement + text.substring(end)
    val updatedCursor = start + replacement.length
    return TextInputValue(
        text = updatedText,
        cursorOffset = updatedCursor,
        selectionAnchor = updatedCursor,
    )
}

private fun TextInputValue.withCollapsedCursor(offset: Int): TextInputValue =
    copy(cursorOffset = offset, selectionAnchor = offset)

private fun TextInputValue.withScalarBoundaries(): TextInputValue {
    val cursor = text.coerceToScalarBoundary(cursorOffset)
    val anchor = text.coerceToScalarBoundary(selectionAnchor)
    return if (cursor == cursorOffset && anchor == selectionAnchor) {
        this
    } else {
        copy(cursorOffset = cursor, selectionAnchor = anchor)
    }
}

private fun String.previousWordGroupStart(offset: Int): Int {
    var current = coerceToScalarBoundary(offset)
    while (current > 0) {
        val previous = previousScalar(current)
        if (!substring(previous, current).all(Char::isWhitespace)) break
        current = previous
    }
    if (current == 0) return 0

    val previous = previousScalar(current)
    val kind = substring(previous, current).wordGroupKind()
    current = previous
    while (current > 0) {
        val candidate = previousScalar(current)
        if (substring(candidate, current).wordGroupKind() != kind) break
        current = candidate
    }
    return current
}

private fun String.wordGroupKind(): WordGroupKind = when {
    all(Char::isWhitespace) -> WordGroupKind.Whitespace
    length == 1 && (first().isLetterOrDigit() || first() == '_') -> WordGroupKind.Word
    else -> WordGroupKind.Symbol
}

private enum class WordGroupKind {
    Whitespace,
    Word,
    Symbol,
}

private fun String.hardLineStart(offset: Int): Int {
    val safe = coerceToScalarBoundary(offset)
    return if (safe == 0) 0 else lastIndexOf('\n', safe - 1) + 1
}

private fun String.hardLineEnd(offset: Int): Int {
    val safe = coerceToScalarBoundary(offset)
    return indexOf('\n', safe).takeIf { it >= 0 } ?: length
}

private fun String.coerceToScalarBoundary(offset: Int): Int {
    val safe = offset.coerceIn(0, length)
    return if (
        safe in 1 until length &&
        this[safe - 1].isHighSurrogate() &&
        this[safe].isLowSurrogate()
    ) {
        safe - 1
    } else {
        safe
    }
}

private fun String.previousScalar(offset: Int): Int {
    val safe = coerceToScalarBoundary(offset)
    return if (safe >= 2 && this[safe - 2].isHighSurrogate() && this[safe - 1].isLowSurrogate()) {
        safe - 2
    } else {
        (safe - 1).coerceAtLeast(0)
    }
}

private fun String.nextScalar(offset: Int): Int {
    val safe = coerceToScalarBoundary(offset)
    return if (safe + 1 < length && this[safe].isHighSurrogate() && this[safe + 1].isLowSurrogate()) {
        safe + 2
    } else {
        (safe + 1).coerceAtMost(length)
    }
}

private fun String.isSingleScalar(): Boolean = length == 1 ||
    (length == 2 && first().isHighSurrogate() && last().isLowSurrogate())

private const val MaximumHistoryEntries: Int = 100
