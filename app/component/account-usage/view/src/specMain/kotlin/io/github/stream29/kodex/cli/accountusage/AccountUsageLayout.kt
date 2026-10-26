package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.Composable

@Composable
internal expect fun AccountUsageFrame(content: @Composable () -> Unit)

@Composable
internal expect fun AccountUsageActions(refresh: @Composable () -> Unit, reset: @Composable () -> Unit)
