package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.compose.DirectionalFocusBoundary

@Composable
public actual fun WithBrowserComponentRenderers(content: @Composable () -> Unit) {
    WithUiRenderer(BrowserUiRenderer) {
        DirectionalFocusBoundary(content)
    }
}
