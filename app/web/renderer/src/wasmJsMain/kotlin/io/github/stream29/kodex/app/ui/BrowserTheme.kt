package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.browser.BrowserTheme

@Composable
internal fun BrowserUiTheme(content: @Composable () -> Unit): Unit = BrowserTheme {
    WithUiEditorSurface(content)
}
