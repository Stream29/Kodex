package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface AccountUsageLayoutRenderer {
    @Composable
    public fun AccountUsageFrame(content: @Composable () -> Unit): Unit

    @Composable
    public fun AccountUsageActions(refresh: @Composable () -> Unit, reset: @Composable () -> Unit): Unit
}

@Composable
public expect fun WithAccountUsageLayoutRenderer(renderer: AccountUsageLayoutRenderer, content: @Composable () -> Unit): Unit
