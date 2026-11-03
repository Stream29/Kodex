package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.settings.SettingsRootRenderer

/** Graphical root; capability decisions are supplied by the acquired host, not this renderer. */
@Composable
public expect fun rememberComposeSettingsRootRenderer(): SettingsRootRenderer
