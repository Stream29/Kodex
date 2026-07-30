package io.github.stream29.codex.lite.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.focusCursor
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPasteEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.codex.lite.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.codex.lite.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.codex.lite.utils.terminaltext.terminalCellWidth

/** Rendered rows and cursor position for a [TextInput]. */
@Immutable
public data class TextInputLayout(
    public val lines: List<String>,
    public val cursorColumn: Int,
    public val cursorRow: Int,
) {
    /** Display text after horizontal clipping and any caller-provided line prefixes. */
    public val renderedText: String
        get() = lines.joinToString("\n")

    public val rowCount: Int
        get() = lines.size

    public companion object {
        /**
         * Creates a single-row-per-hard-line layout with the cursor's line horizontally centered
         * around the cursor where possible.
         */
        public fun create(
            value: TextInputValue,
            width: Int,
            firstLinePrefix: String = "",
            continuationLinePrefix: String = firstLinePrefix,
        ): TextInputLayout {
            val safeWidth = width.coerceAtLeast(1)
            val safeOffset = value.cursorOffset.coerceIn(0, value.text.length)
            val source = value.text.split('\n')
            val cursorRow = value.text.substring(0, safeOffset).count { it == '\n' }
            val lineStart = if (safeOffset == 0) 0 else value.text.lastIndexOf('\n', safeOffset - 1) + 1
            val cursorInLine = safeOffset - lineStart
            var cursorColumn = 0
            val lines = source.mapIndexed { index, line ->
                val prefix = if (index == 0) firstLinePrefix else continuationLinePrefix
                val available = (safeWidth - prefix.terminalCellWidth()).coerceAtLeast(0)
                if (index == cursorRow) {
                    val before = line.substring(0, cursorInLine.coerceIn(0, line.length))
                    val visibleBefore = before.takeLastFittingTerminalWidth(available)
                    val visibleAfter = line.substring(before.length).takeFirstFittingTerminalWidth(
                        available - visibleBefore.terminalCellWidth(),
                    )
                    cursorColumn = prefix.terminalCellWidth() + visibleBefore.terminalCellWidth()
                    prefix + visibleBefore + visibleAfter
                } else {
                    prefix + line.takeFirstFittingTerminalWidth(available)
                }
            }
            return TextInputLayout(lines, cursorColumn, cursorRow)
        }
    }
}

/**
 * A focusable terminal text input backed by [state].
 *
 * [onKeyEvent] receives an event before the default editing behavior. Return `true` to consume it,
 * for example to implement application-specific newline or submit shortcuts.
 */
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

    fun applyEdit(edit: TextInputEdit) {
        if (state.edit(edit)) latestOnValueChanged.value?.invoke(state.value)
    }

    val inputModifier = if (enabled) {
        modifier
            .then(requesterModifier)
            .focusable(autoFocus = autoFocus)
            .focusCursor(layout.cursorColumn, layout.cursorRow)
            .onPasteEvent { event ->
                applyEdit(TextInputEdit.Insert(event.text.normalizeLineEndings()))
                true
            }
            .onKeyEvent { event ->
                when {
                    latestOnKeyEvent.value?.invoke(event) == true -> true
                    event.key == "Backspace" -> {
                        applyEdit(TextInputEdit.DeleteBeforeCursor)
                        true
                    }

                    event.key == "Delete" -> {
                        applyEdit(TextInputEdit.DeleteAtCursor)
                        true
                    }

                    event.key == "ArrowLeft" -> {
                        applyEdit(TextInputEdit.MoveCursorLeft)
                        true
                    }

                    event.key == "ArrowRight" -> {
                        applyEdit(TextInputEdit.MoveCursorRight)
                        true
                    }

                    event.key == "Home" -> {
                        applyEdit(TextInputEdit.MoveCursorToStart)
                        true
                    }

                    event.key == "End" -> {
                        applyEdit(TextInputEdit.MoveCursorToEnd)
                        true
                    }

                    !event.ctrl && !event.alt && event.key.isSingleScalar() -> {
                        applyEdit(TextInputEdit.Insert(event.key))
                        true
                    }

                    else -> false
                }
            }
    } else {
        modifier
    }
    Text(
        value = layout.renderedText,
        modifier = inputModifier,
        textStyle = if (enabled) TextStyle.Unspecified else TextStyle.Dim,
    )
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

private fun String.isSingleScalar(): Boolean = length == 1 ||
    (length == 2 && first().isHighSurrogate() && last().isLowSurrogate())
