package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase

/** Small terminal-ready status label for one execution phase. */
public fun AgentExecutionPhase.label(): String = when (this) {
    AgentExecutionPhase.Empty -> "Idle"
    AgentExecutionPhase.UserMessage -> "User message"
    AgentExecutionPhase.Responding -> "Generating response"
    AgentExecutionPhase.AssistantMessage -> "Assistant message"
    AgentExecutionPhase.ToolPending -> "Waiting for tool output"
    AgentExecutionPhase.ToolCompleted -> "Tool completed"
    AgentExecutionPhase.ExternalWrite -> "Saving"
    AgentExecutionPhase.Compacting -> "Compacting context"
}
