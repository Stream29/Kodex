package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

@Composable
public actual fun WithMosaicComponentRenderers(content: @Composable () -> Unit) {
    WithUiRenderer(MosaicUiRenderer, content)
}
