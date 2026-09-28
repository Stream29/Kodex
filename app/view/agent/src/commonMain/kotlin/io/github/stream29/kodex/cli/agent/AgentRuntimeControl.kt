package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.rpc.models.AgentStateValue

/** Primary control available for one Agent runtime in its compact toolbar. */
public enum class AgentRuntimeControl {
    Stop,
    ClearPending,
    Resume,
}

/** Presentation only: the backend still checks command admission at execution. */
public fun AgentStateValue.runtimeControl(running: Boolean): AgentRuntimeControl = when {
    running -> AgentRuntimeControl.Stop
    this is AgentStateValue.ToolPending -> AgentRuntimeControl.ClearPending
    else -> AgentRuntimeControl.Resume
}

public fun AgentStateValue.canEditHistory(running: Boolean): Boolean =
    !running && this !is AgentStateValue.RequestResponse &&
        this != AgentStateValue.ExternalWrite && this != AgentStateValue.Compacting

public fun AgentStateValue.canCompact(running: Boolean): Boolean =
    !running && (this == AgentStateValue.UserMessage ||
        this == AgentStateValue.AssistantMessage || this == AgentStateValue.ToolCompleted)
