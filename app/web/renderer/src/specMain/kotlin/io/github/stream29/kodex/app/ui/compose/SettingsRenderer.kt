package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.SettingsRenderer

/** Remembers graphical field/menu presentation, not a settings ViewModel or mutable settings cache. */
@Composable
public expect fun rememberComposeSettingsRenderer(): SettingsRenderer
