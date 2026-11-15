package io.github.stream29.kodex.agentruntime.impl

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState

/**
 * Builds the root Agent runtime.
 *
 * Spawned `exec_command` subprocesses receive `KODEX_HOME` from
 * [KodexAgentDependencies.contextSettings] and `KODEX_AGENT_ID` when
 * [KodexAgentState.agentId] is non-null.
 */
public expect fun KodexAgentState.buildMasterAgentRuntime(
    dependencies: KodexAgentDependencies,
): AgentRuntime
