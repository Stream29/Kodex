package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.app.ApplicationPopupRenderer

/** Client-side URL launching only, not a backend OAuth callback listener or credential capability. */
@Composable
public expect fun rememberComposeApplicationPopupRenderer(
    openExternalUrl: suspend (String) -> Boolean,
): ApplicationPopupRenderer
