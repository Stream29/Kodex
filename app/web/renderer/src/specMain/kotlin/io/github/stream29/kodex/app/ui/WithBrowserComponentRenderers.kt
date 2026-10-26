package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

/** Installs only typed drawing/layout leaves for one host surface. */
@Composable
public expect fun WithBrowserComponentRenderers(content: @Composable () -> Unit): Unit
