package io.github.stream29.codex.lite.agentruntime.contract

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates an agent run above the atomic AgentState layer.
 *
 * Runtime implementations may compose as decorators to add tool execution,
 * hooks, skills, AGENTS.md support, or temporary context injection. Their
 * public surface deliberately hides compaction; [resume] handles it whenever
 * necessary before continuing the run.
 */
public interface CodexAgentRuntime {
    /** Current coarse-grained phase of the underlying agent state. */
    public val state: StateFlow<CodexAgentStateValue>

    /** Latest globally visible storage snapshot index. */
    public val latestIndex: StateFlow<Int>

    /** Read-only persisted data of the underlying agent state. */
    public val storage: CodexAgentStorage

    /** Executes this runtime layer's resume operation and exposes raw stream events. */
    public fun resume(): Flow<ResponsesStreamEvent>
}
