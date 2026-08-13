package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.AgentExecutionState

/** Primary control available for one Agent runtime in its compact toolbar. */
public enum class AgentRuntimeControl {
    Stop,
    ClearPending,
    Resume,
}

/** Derives the control from the same contract capabilities that authorize it. */
public fun AgentExecutionState.runtimeControl(): AgentRuntimeControl = when {
    capabilities.canCancel -> AgentRuntimeControl.Stop
    capabilities.canClearPending -> AgentRuntimeControl.ClearPending
    else -> AgentRuntimeControl.Resume
}
