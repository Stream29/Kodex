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

/** Adds orthogonal resource activity without redefining the Agent's primary execution state. */
public fun AgentRuntimeRenderState.label(activeShellSessionCount: Int): String {
    val shellActivity = activeShellSessionLabel(activeShellSessionCount) ?: return label()
    return "${label()} · $shellActivity"
}

/** Compact terminal wording for Unified Exec sessions that remain in the active registry. */
public fun activeShellSessionLabel(count: Int): String? {
    require(count >= 0) { "Active shell session count cannot be negative." }
    if (count == 0) return null
    val suffix = if (count == 1) "shell session" else "shell sessions"
    return "$count $suffix"
}
