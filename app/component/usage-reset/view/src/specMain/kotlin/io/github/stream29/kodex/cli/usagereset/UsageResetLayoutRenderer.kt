package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface UsageResetLayoutRenderer {
    @Composable
    public fun UsageResetOptionButton(label: String, enabled: Boolean, onClick: () -> Unit): Unit

    @Composable
    public fun UsageResetAction(
    label: String, primary: Boolean, autoFocus: Boolean, onClick: () -> Unit,
): Unit

    @Composable
    public fun UsageResetActions(content: @Composable () -> Unit): Unit
}

@Composable
public expect fun WithUsageResetLayoutRenderer(renderer: UsageResetLayoutRenderer, content: @Composable () -> Unit): Unit
