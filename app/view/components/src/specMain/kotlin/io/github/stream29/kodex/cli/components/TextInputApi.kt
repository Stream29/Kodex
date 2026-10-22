@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString

/** Immutable text and active UTF-16 cursor offset. */
@Immutable
public data class TextInputValue(
    public val text: String = "",
    public val cursorOffset: Int = text.length,
) {
    init {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the text." }
    }
}

/** A single text-input mutation or compatibility cursor movement. */
public sealed interface TextInputEdit {
    /**
     * Inserts [text] at the current cursor.
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
public expect fun TextInputEdit.applyTo(value: TextInputValue): TextInputValue

/**
 * Mutable, composition-observable state for a terminal text input.
 *
 * Preferred vertical column, bounded undo/redo history, and viewport position are local editor
 * state. Application callers synchronize text and the active cursor.
 */
@Stable
public expect class TextInputState(initialValue: TextInputValue = TextInputValue()) {
    public var value: TextInputValue
        private set

    public var scrollOffset: Int
        private set

    public val canUndo: Boolean

    public val canRedo: Boolean

    /** Applies [edit] and returns whether it changed the text or cursor. */
    public fun edit(edit: TextInputEdit): Boolean

    /**
     * Moves the active cursor.
     *
     * [layout] is required for visual [TextInputMovement.Up] and [TextInputMovement.Down].
     */
    public fun moveCursor(
        movement: TextInputMovement,
        layout: TextInputLayout? = null,
    ): Boolean

    /** Places the cursor at the nearest Unicode-scalar boundary. */
    public fun placeCursor(offset: Int): Boolean

    /** Restores the previous text transaction, including its cursor. */
    public fun undo(): Boolean

    /** Reapplies the next text transaction, including its cursor. */
    public fun redo(): Boolean

    /** Replaces the draft and clears all editor-local transient state. */
    public fun reset(value: TextInputValue = TextInputValue())
}

/** Remembers one [TextInputState] for this composition location. */
@Composable
public expect fun rememberTextInputState(initialValue: TextInputValue = TextInputValue()): TextInputState

/** Rendered rows, source mappings, cursor position, and optional viewport for a [TextInput]. */
@Immutable
public expect class TextInputLayout {
    public val width: Int

    public val cursorColumn: Int

    public val cursorRow: Int

    public val cursorContentColumn: Int

    /** Display text before applying a bounded vertical viewport. */
    public val renderedText: String

    public val lines: List<String>

    public val rowCount: Int

    public val visibleRowCount: Int

    /** Returns a copy whose renderer exposes at most [maximumRows] visual rows. */
    public fun withViewportRows(maximumRows: Int): TextInputLayout

    /** Builds text for a clamped slice of visual rows. */
    public fun visibleText(
        firstRow: Int,
        rowCount: Int = visibleRowCount,
    ): AnnotatedString

    /** Maps a visual-row terminal-cell position to the nearest editable source boundary. */
    public fun sourceOffsetAt(column: Int, row: Int): Int

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
        ): TextInputLayout
    }
}

/**
 * A focusable terminal text input backed by [state].
 * [readOnly] preserves focus and cursor navigation but blocks edits and custom key actions.
 */
@Composable
public expect fun TextInput(
    state: TextInputState,
    layout: TextInputLayout,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    autoFocus: Boolean = false,
    enabled: Boolean = true,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
    readOnly: Boolean = false,
): Unit
