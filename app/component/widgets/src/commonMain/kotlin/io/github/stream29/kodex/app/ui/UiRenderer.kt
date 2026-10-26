package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

private val LocalUiRenderer = staticCompositionLocalOf<UiRenderer> { error("Missing host UiRenderer") }

@Composable
public actual fun WithUiRenderer(renderer: UiRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiRenderer provides renderer, content = content)
}

@Composable
public actual fun UiTheme(content: @Composable () -> Unit): Unit =
    LocalUiRenderer.current.UiTheme(content)

@Composable
public actual fun UiColumn(content: @Composable () -> Unit): Unit =
    LocalUiRenderer.current.UiColumn(content)

@Composable
public actual fun UiActions(content: @Composable () -> Unit): Unit =
    LocalUiRenderer.current.UiActions(content)

@Composable
public actual fun UiText(text: String, role: UiTextRole, maximumLines: Int): Unit =
    LocalUiRenderer.current.UiText(text, role, maximumLines)

@Composable
public actual fun UiProse(text: String, role: UiTextRole): Unit =
    LocalUiRenderer.current.UiProse(text, role)

@Composable
public actual fun UiAction(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole): Unit =
    LocalUiRenderer.current.UiAction(label, onClick, enabled, autoFocus, role)

@Composable
public actual fun UiEditor(text: String, cursorOffset: Int, onChange: (String, Int) -> Long?, onSubmit: (text: String, cursorOffset: Int) -> Unit, label: String, enabled: Boolean, readOnly: Boolean, autoFocus: Boolean, multiline: Boolean, maximumLines: Int, submitKey: UiSubmitKey, revision: Long?): Unit =
    LocalUiRenderer.current.UiEditor(text, cursorOffset, onChange, onSubmit, label, enabled, readOnly, autoFocus, multiline, maximumLines, submitKey, revision)

@Composable
internal actual fun TextInputFrame(label: String, content: @Composable () -> Unit): Unit =
    LocalUiRenderer.current.TextInputFrame(label, content)
