package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue

/** Primary control available for one Agent runtime in its compact toolbar. */
public enum class AgentRuntimeControl {
    Stop,
    ClearPending,
    Resume,
}

/** Derives the control from the same runtime facts that authorize its action. */
public fun AgentRuntimeViewState.runtimeControl(): AgentRuntimeControl = when {
    running -> AgentRuntimeControl.Stop
    agentState is KodexAgentStateValue.ToolPending -> AgentRuntimeControl.ClearPending
    else -> AgentRuntimeControl.Resume
}
