package io.github.stream29.codex.lite.cli.agent

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue

/** Typed render model. Terminal wording belongs here, never in AgentState. */
public sealed interface AgentRuntimeRenderState {
    public data object Idle : AgentRuntimeRenderState
    public data object RequestStarted : AgentRuntimeRenderState
    public data class Streaming(
        public val kind: AgentStreamKind,
    ) : AgentRuntimeRenderState

    public data object ToolPending : AgentRuntimeRenderState
    public data object Compacting : AgentRuntimeRenderState
    public data object ExternalWrite : AgentRuntimeRenderState
}

public fun AgentRuntimeViewState.toRenderState(): AgentRuntimeRenderState = when (val current = agentState) {
    is CodexAgentStateValue.RequestResponse -> when (current) {
        CodexAgentStateValue.RequestResponse.Started -> AgentRuntimeRenderState.RequestStarted
        is CodexAgentStateValue.RequestResponse.Message -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Message)
        is CodexAgentStateValue.RequestResponse.AgentMessage -> AgentRuntimeRenderState.Streaming(AgentStreamKind.AgentMessage)
        is CodexAgentStateValue.RequestResponse.Reasoning -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Reasoning)
        is CodexAgentStateValue.RequestResponse.ToolCall -> AgentRuntimeRenderState.Streaming(AgentStreamKind.ToolCall)
        is CodexAgentStateValue.RequestResponse.Unknown -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Unknown)
    }

    is CodexAgentStateValue.ToolPending -> AgentRuntimeRenderState.ToolPending
    CodexAgentStateValue.Compacting -> AgentRuntimeRenderState.Compacting
    CodexAgentStateValue.ExternalWrite -> AgentRuntimeRenderState.ExternalWrite
    else -> AgentRuntimeRenderState.Idle
}

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
