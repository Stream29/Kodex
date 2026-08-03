package io.github.stream29.kodex.cli.agent

/** Small terminal-ready status label for the current typed render state. */
public fun AgentRuntimeRenderState.label(): String = when (this) {
    AgentRuntimeRenderState.Idle -> "Idle"
    AgentRuntimeRenderState.RequestStarted -> "Starting response"
    is AgentRuntimeRenderState.Streaming -> when (kind) {
        AgentStreamKind.Message -> "Generating response"
        AgentStreamKind.AgentMessage -> "Sending agent message"
        AgentStreamKind.Reasoning -> "Reasoning"
        AgentStreamKind.ToolCall -> "Running tool"
        AgentStreamKind.Unknown -> "Receiving response item"
    }

    AgentRuntimeRenderState.ToolPending -> "Waiting for tool output"
    AgentRuntimeRenderState.Compacting -> "Compacting context"
    AgentRuntimeRenderState.ExternalWrite -> "Saving"
}
