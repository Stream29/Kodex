package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiColumn

internal object BrowserLoginLayoutRenderer : LoginLayoutRenderer {
    @Composable
    override fun LoginBodyFrame(content: @Composable () -> Unit) { UiColumn(content) }

    @Composable
    override fun LoginActions(primaryLabel: String?, primaryAction: (() -> Unit)?, onDismissRequest: (() -> Unit)?) {
        require((primaryLabel == null) == (primaryAction == null))
        UiActions {
            onDismissRequest?.let { UiAction("Cancel", onClick = it) }
            primaryLabel?.let { UiAction(it, onClick = requireNotNull(primaryAction)) }
        }
    }
}
