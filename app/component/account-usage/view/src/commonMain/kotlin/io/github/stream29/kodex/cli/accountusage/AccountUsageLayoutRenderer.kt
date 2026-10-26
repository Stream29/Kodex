package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.*

private val LocalAccountUsageLayoutRenderer = staticCompositionLocalOf<AccountUsageLayoutRenderer> { error("Missing host AccountUsageLayoutRenderer") }

@Composable
public actual fun WithAccountUsageLayoutRenderer(renderer: AccountUsageLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccountUsageLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun AccountUsageFrame(content: @Composable () -> Unit): Unit =
    LocalAccountUsageLayoutRenderer.current.AccountUsageFrame(content)

@Composable
internal actual fun AccountUsageActions(refresh: @Composable () -> Unit, reset: @Composable () -> Unit): Unit =
    LocalAccountUsageLayoutRenderer.current.AccountUsageActions(refresh, reset)
