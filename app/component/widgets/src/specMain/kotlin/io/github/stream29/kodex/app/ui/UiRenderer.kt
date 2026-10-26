package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

/** Renderer leaves take explicit arguments; defaults belong to the non-virtual widget functions. */
public interface UiRenderer {
    @Composable
    public fun UiTheme(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiColumn(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiActions(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiText(
        text: String, role: UiTextRole, maximumLines: Int,
    ): Unit

    @Composable
    public fun UiProse(text: String, role: UiTextRole): Unit

    @Composable
    public fun UiAction(
        label: String,
        onClick: () -> Unit,
        enabled: Boolean,
        autoFocus: Boolean,
        role: UiActionRole,
    ): Unit

    @Composable
    public fun UiEditor(
        text: String,
        cursorOffset: Int,
        onChange: (String, Int) -> Long?,
        onSubmit: (text: String, cursorOffset: Int) -> Unit,
        label: String,
        enabled: Boolean,
        readOnly: Boolean,
        autoFocus: Boolean,
        multiline: Boolean,
        maximumLines: Int,
        submitKey: UiSubmitKey,
        revision: Long?,
    ): Unit

    @Composable
    public fun TextInputFrame(label: String, content: @Composable () -> Unit): Unit
}

@Composable
public expect fun WithUiRenderer(renderer: UiRenderer, content: @Composable () -> Unit): Unit
