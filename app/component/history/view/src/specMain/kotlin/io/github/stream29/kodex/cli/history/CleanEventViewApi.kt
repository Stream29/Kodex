package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry

/** Renders one committed clean event without flattening its domain model first. */
@Composable
public expect fun StableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry? = null,
): Unit

/** Renders one current unfinished clean event without reducing it to raw history. */
@Composable
public expect fun UnstableCleanEvent.render(
    shellSessions: AgentShellSessionRegistry? = null,
): Unit
