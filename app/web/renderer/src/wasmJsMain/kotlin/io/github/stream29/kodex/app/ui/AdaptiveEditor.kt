package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalAdaptiveEditor = staticCompositionLocalOf { false }

@Composable
internal fun WithAdaptiveEditor(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAdaptiveEditor provides true, content = content)
}
