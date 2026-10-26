package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.rpc.models.AgentStateValue

public actual fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}

public actual fun AgentStateValue.runtimeControl(running: Boolean): AgentRuntimeControl = when {
    running -> AgentRuntimeControl.Stop
    this is AgentStateValue.ToolPending -> AgentRuntimeControl.ClearPending
    else -> AgentRuntimeControl.Resume
}

public actual fun AgentStateValue.canEditHistory(running: Boolean): Boolean =
    !running && this !is AgentStateValue.RequestResponse &&
        this != AgentStateValue.ExternalWrite && this != AgentStateValue.Compacting

public actual fun AgentStateValue.canCompact(running: Boolean): Boolean =
    !running && (this == AgentStateValue.UserMessage ||
        this == AgentStateValue.AssistantMessage || this == AgentStateValue.ToolCompleted)
