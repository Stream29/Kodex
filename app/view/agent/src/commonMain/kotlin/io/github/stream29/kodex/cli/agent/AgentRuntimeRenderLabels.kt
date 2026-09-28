package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.rpc.models.AgentStateValue

/** Small terminal-ready status label for one execution phase. */
public fun AgentStateValue.label(): String = when (this) {
    AgentStateValue.Empty -> "Idle"
    AgentStateValue.UserMessage -> "User message"
    is AgentStateValue.RequestResponse -> "Generating response"
    AgentStateValue.AssistantMessage -> "Assistant message"
    is AgentStateValue.ToolPending -> "Waiting for tool output"
    AgentStateValue.ToolCompleted -> "Tool completed"
    AgentStateValue.ExternalWrite -> "Saving"
    AgentStateValue.Compacting -> "Compacting context"
}
