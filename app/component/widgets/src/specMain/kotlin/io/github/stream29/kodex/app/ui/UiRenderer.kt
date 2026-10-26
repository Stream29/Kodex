package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface UiRenderer {
    @Composable
    public fun UiTheme(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiColumn(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiActions(content: @Composable () -> Unit): Unit

    @Composable
    public fun UiText(
        text: String, role: UiTextRole = UiTextRole.Body, maximumLines: Int = Int.MAX_VALUE,
    ): Unit

    @Composable
    public fun UiProse(text: String, role: UiTextRole = UiTextRole.Body): Unit

    @Composable
    public fun UiAction(
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        autoFocus: Boolean = false,
        role: UiActionRole = UiActionRole.Normal,
    ): Unit

    @Composable
    public fun UiEditor(
        text: String,
        cursorOffset: Int,
        onChange: (String, Int) -> Long?,
        onSubmit: (text: String, cursorOffset: Int) -> Unit,
        label: String = "",
        enabled: Boolean = true,
        readOnly: Boolean = false,
        autoFocus: Boolean = false,
        multiline: Boolean = true,
        maximumLines: Int = 6,
        submitKey: UiSubmitKey = UiSubmitKey.Enter,
        revision: Long? = null,
    ): Unit

    @Composable
    public fun TextInputFrame(label: String, content: @Composable () -> Unit): Unit
}

@Composable
public expect fun WithUiRenderer(renderer: UiRenderer, content: @Composable () -> Unit): Unit
