package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Immutable text and UTF-16 cursor offset owned by a [TextInputState]. */
@Immutable
public data class TextInputValue(
    public val text: String = "",
    public val cursorOffset: Int = text.length,
) {
    init {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the text." }
    }
}

/** A single text-input mutation. */
public sealed interface TextInputEdit {
    public data class Insert(public val text: String) : TextInputEdit

    public data object DeleteBeforeCursor : TextInputEdit

    public data object DeleteAtCursor : TextInputEdit

    public data object MoveCursorLeft : TextInputEdit

    public data object MoveCursorRight : TextInputEdit

    public data object MoveCursorToStart : TextInputEdit

    public data object MoveCursorToEnd : TextInputEdit
}

/** Applies this edit to [value] using Unicode-scalar cursor semantics. */
public fun TextInputEdit.applyTo(value: TextInputValue): TextInputValue = when (this) {
    is TextInputEdit.Insert -> value.copy(
        text = value.text.substring(0, value.cursorOffset) + text + value.text.substring(value.cursorOffset),
        cursorOffset = value.cursorOffset + text.length,
    )

    TextInputEdit.DeleteBeforeCursor -> {
        val start = value.text.previousScalar(value.cursorOffset)
        if (start == value.cursorOffset) value else value.copy(
            text = value.text.removeRange(start, value.cursorOffset),
            cursorOffset = start,
        )
    }

    TextInputEdit.DeleteAtCursor -> {
        val end = value.text.nextScalar(value.cursorOffset)
        if (end == value.cursorOffset) value else value.copy(text = value.text.removeRange(value.cursorOffset, end))
    }

    TextInputEdit.MoveCursorLeft -> value.copy(cursorOffset = value.text.previousScalar(value.cursorOffset))
    TextInputEdit.MoveCursorRight -> value.copy(cursorOffset = value.text.nextScalar(value.cursorOffset))
    TextInputEdit.MoveCursorToStart -> value.copy(cursorOffset = 0)
    TextInputEdit.MoveCursorToEnd -> value.copy(cursorOffset = value.text.length)
}

/**
 * Mutable, composition-observable state for a terminal text input.
 *
 * This owns the input's edit dispatch so future selection, undo, and redo state can stay with the
 * editor rather than with each application caller.
 */
@Stable
public class TextInputState(initialValue: TextInputValue = TextInputValue()) {
    public var value: TextInputValue by mutableStateOf(initialValue)
        private set

    /** Applies [edit] and returns whether it changed the text or cursor. */
    public fun edit(edit: TextInputEdit): Boolean {
        val updated = edit.applyTo(value)
        if (updated == value) return false
        value = updated
        return true
    }

    /** Replaces the draft and clears editor-local transient state in future state implementations. */
    public fun reset(value: TextInputValue = TextInputValue()) {
        this.value = value
    }
}

/** Remembers one [TextInputState] for this composition location. */
@Composable
public fun rememberTextInputState(initialValue: TextInputValue = TextInputValue()): TextInputState =
    remember { TextInputState(initialValue) }

private fun String.previousScalar(offset: Int): Int {
    val safe = offset.coerceIn(0, length)
    return if (safe >= 2 && this[safe - 2].isHighSurrogate() && this[safe - 1].isLowSurrogate()) {
        safe - 2
    } else {
        (safe - 1).coerceAtLeast(0)
    }
}

private fun String.nextScalar(offset: Int): Int {
    val safe = offset.coerceIn(0, length)
    return if (safe + 1 < length && this[safe].isHighSurrogate() && this[safe + 1].isLowSurrogate()) {
        safe + 2
    } else {
        (safe + 1).coerceAtMost(length)
    }
}
