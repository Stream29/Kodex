package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

internal object BrowserUiRenderer : UiRenderer {
    @Composable
    override fun UiTheme(content: @Composable () -> Unit): Unit =
        BrowserUiTheme(content)

    @Composable
    override fun UiColumn(content: @Composable () -> Unit): Unit =
        BrowserUiColumn(content)

    @Composable
    override fun UiActions(content: @Composable () -> Unit): Unit =
        BrowserUiActions(content)

    @Composable
    override fun UiText(text: String, role: UiTextRole, maximumLines: Int): Unit =
        BrowserUiText(text, role, maximumLines)

    @Composable
    override fun UiProse(text: String, role: UiTextRole): Unit =
        BrowserUiProse(text, role)

    @Composable
    override fun UiAction(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean, role: UiActionRole): Unit =
        BrowserUiAction(label, onClick, enabled, autoFocus, role)

    @Composable
    override fun UiEditor(text: String, cursorOffset: Int, onChange: (String, Int) -> Long?, onSubmit: (text: String, cursorOffset: Int) -> Unit, label: String, enabled: Boolean, readOnly: Boolean, autoFocus: Boolean, multiline: Boolean, maximumLines: Int, submitKey: UiSubmitKey, revision: Long?): Unit =
        BrowserUiEditor(text, cursorOffset, onChange, onSubmit, label, enabled, readOnly, autoFocus, multiline, maximumLines, submitKey, revision)

    @Composable
    override fun TextInputFrame(label: String, content: @Composable () -> Unit): Unit =
        BrowserTextInputFrame(label, content)

}
