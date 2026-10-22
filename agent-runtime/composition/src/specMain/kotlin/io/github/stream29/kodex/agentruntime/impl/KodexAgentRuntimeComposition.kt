package io.github.stream29.kodex.agentruntime.impl

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState

/** Builds the root Agent runtime. */
public expect fun KodexAgentState.buildMasterAgentRuntime(
    dependencies: KodexAgentDependencies,
): AgentRuntime
