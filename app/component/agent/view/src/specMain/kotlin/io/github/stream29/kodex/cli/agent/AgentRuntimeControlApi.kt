package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.rpc.models.AgentStateValue

/** Primary control available for one Agent runtime in its compact toolbar. */
public enum class AgentRuntimeControl {
    Stop,
    ClearPending,
    Resume,
}

/** Presentation only: the backend still checks command admission at execution. */
public expect fun AgentStateValue.runtimeControl(running: Boolean): AgentRuntimeControl

public expect fun AgentStateValue.canEditHistory(running: Boolean): Boolean

public expect fun AgentStateValue.canCompact(running: Boolean): Boolean

public expect fun AgentRuntimeControl.label(): String
