package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Loads a state-layer implementation from [storage] as a child of this scope.
 *
 * Construction is suspend because storage reads may be asynchronous. The
 * initial phase is reconstructed from persisted history rather than assumed
 * from the newest global index, which may belong to another timeline.
 *
 * [contextSettings] is observed for every normal Responses request. This state
 * resolves the complete context prefix internally; the prefix is never
 * persisted or included in remote compaction input.
 *
 * [mcpService] is sampled once for every normal Responses request and remote
 * compaction request. AgentState derives the matching tool-search definition
 * internally and never closes the application-owned service.
 * Client and storage are also borrowed. Suspending operations execute in the
 * caller's coroutine; cancelling the created child scope does not close these
 * resources or promise cancellation/rollback of unrelated caller jobs.
 *
 * @throws kotlinx.coroutines.CancellationException when loading is cancelled.
 * Storage read failures propagate after cancelling the newly created scope.
 */
public expect suspend fun CoroutineScope.KodexAgentState(
    client: OpenAiClient,
    storage: MutableKodexAgentStorage,
    contextSettings: StateFlow<AgentContextSettings>,
    mcpService: McpService,
): KodexAgentState
