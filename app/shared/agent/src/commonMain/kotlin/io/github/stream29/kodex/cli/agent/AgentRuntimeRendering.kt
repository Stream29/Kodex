package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue

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
    is KodexAgentStateValue.RequestResponse -> when (current) {
        KodexAgentStateValue.RequestResponse.Started -> AgentRuntimeRenderState.RequestStarted
        is KodexAgentStateValue.RequestResponse.Message -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Message)
        is KodexAgentStateValue.RequestResponse.AgentMessage -> AgentRuntimeRenderState.Streaming(AgentStreamKind.AgentMessage)
        is KodexAgentStateValue.RequestResponse.Reasoning -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Reasoning)
        is KodexAgentStateValue.RequestResponse.ToolCall -> AgentRuntimeRenderState.Streaming(AgentStreamKind.ToolCall)
        is KodexAgentStateValue.RequestResponse.Unknown -> AgentRuntimeRenderState.Streaming(AgentStreamKind.Unknown)
    }

    is KodexAgentStateValue.ToolPending -> AgentRuntimeRenderState.ToolPending
    KodexAgentStateValue.Compacting -> AgentRuntimeRenderState.Compacting
    KodexAgentStateValue.ExternalWrite -> AgentRuntimeRenderState.ExternalWrite
    else -> AgentRuntimeRenderState.Idle
}
