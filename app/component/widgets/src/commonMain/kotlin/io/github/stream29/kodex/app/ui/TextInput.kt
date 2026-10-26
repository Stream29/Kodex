package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

private val LocalTextInputRenderer = staticCompositionLocalOf<UiTextInputRenderer?> { null }

@Composable
public actual fun WithUiTextInputRenderer(renderer: UiTextInputRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextInputRenderer provides renderer, content = content)
}

@Composable
public actual fun UiTextInput(
    text: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    autoFocus: Boolean,
    onSubmit: () -> Unit,
) {
    val renderer = LocalTextInputRenderer.current
    if (renderer != null) {
        renderer.Field(text, onChange, label, enabled, autoFocus, onSubmit)
        return
    }
    val input = remember { TextInputCursorState(text) }
    val cursor = input.project(text, enabled)
    TextInputFrame(label) {
        UiEditor(
            text = text,
            cursorOffset = cursor,
            onChange = { value, offset ->
                if (input.observe(value, offset)) onChange(value)
                null
            },
            onSubmit = { _, _ -> onSubmit() },
            label = label,
            enabled = enabled,
            autoFocus = autoFocus,
            multiline = false,
            maximumLines = 1,
        )
    }
}

/** Cursor and event fingerprints only; the owner's text is always what the field renders. */
internal class TextInputCursorState(initialText: String) {
    private var cursor by mutableIntStateOf(initialText.length)
    private var externalText = initialText
    private var observedText = initialText

    fun project(text: String, enabled: Boolean = true): Int {
        if (text != externalText) {
            externalText = text
            observedText = text
        }
        if (!enabled) observedText = text
        return cursor.coerceIn(0, text.length)
    }

    fun observe(text: String, cursor: Int): Boolean {
        this.cursor = cursor
        if (text == observedText) return false
        observedText = text
        return true
    }
}
