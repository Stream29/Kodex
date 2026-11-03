package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiActionRole

internal object BrowserUsageResetLayoutRenderer : UsageResetLayoutRenderer {
    @Composable
    override fun UsageResetOptionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
        UiAction(label, enabled = enabled, onClick = onClick)
    }

    @Composable
    override fun UsageResetAction(label: String, primary: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
        UiAction(label, autoFocus = autoFocus, onClick = onClick,
            role = if (primary) UiActionRole.Primary else UiActionRole.Normal)
    }

    @Composable
    override fun UsageResetActions(content: @Composable () -> Unit) {
        UiActions(content)
    }
}
