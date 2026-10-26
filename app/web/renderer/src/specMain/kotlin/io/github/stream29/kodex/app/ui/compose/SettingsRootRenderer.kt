package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.settings.SettingsRootRenderer

/** Browser-facing graphical root; frontend shell execution is not a capability. */
@Composable
public expect fun rememberComposeSettingsRootRenderer(): SettingsRootRenderer
