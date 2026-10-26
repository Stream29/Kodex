package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.app.AgentHistorySeparatorRenderer
import io.github.stream29.kodex.cli.app.AgentStatusRenderer

@Composable
public expect fun rememberComposeAgentStatusRenderer(): AgentStatusRenderer

@Composable
public expect fun rememberComposeAgentHistorySeparatorRenderer(): AgentHistorySeparatorRenderer
