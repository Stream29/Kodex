package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Coarse-grained mutable-state phase visible to runtime callers.
 */
public sealed interface CodexAgentStateEnum {
    public data object Idle : CodexAgentStateEnum

    public sealed interface LlmRequest : CodexAgentStateEnum {
        public data object Response : LlmRequest
        public data object Compact : LlmRequest
    }

    public data object ExternalWrite : CodexAgentStateEnum
}

/**
 * Read-only observable agent state.
 */
public interface CodexAgentState {
    /**
     * Current coarse-grained mutation phase.
     */
    public val state: StateFlow<CodexAgentStateEnum>

    /**
     * Latest globally visible storage snapshot index.
     *
     * Readers should capture this value once and use it to read [storage].
     */
    public val latestIndex: StateFlow<Int>

    /**
     * Read-only persisted agent data.
     */
    public val storage: CodexAgentStorage
}

/**
 * Mutable observable agent state.
 *
 * Implementations must publish [timestamp] atomically with every
 * state-changing method below. They publish [tokenCount] only when the caller
 * has an OpenAI-reported context token count.
 */
public interface MutableCodexAgentState : CodexAgentState {
    /**
     * Executes one model request from the current state, publishes resulting state
     * changes, and returns the raw stream events for this resume operation.
     */
    public fun resume(): Flow<ResponsesStreamEvent>

    /**
     * Requests one server-side context compaction from the current state and
     * returns the state index that publishes the returned checkpoint.
     */
    public suspend fun forcedCompact(): Int

    /**
     * Appends one response history item and updates [timestamp] for the
     * same state transition.
     *
     * @param tokenCount Nullable because OpenAI may not report context token
     * count for this transition; `null` means the `token_count` timeline is
     * left unchanged.
     */
    public suspend fun appendResponseItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int

    /**
     * Appends one update-plan tool call item, publishes one plan snapshot, and
     * records a timestamp for the same state transition.
     */
    public suspend fun appendPlanUpdate(
        item: ResponseItem.FunctionCall,
        plan: UpdatePlanArgs,
    ): Int

    /**
     * Updates model request settings and updates [timestamp] for the same state
     * transition.
     *
     * @param tokenCount Nullable because OpenAI may not report context token
     * count for this transition; `null` means the `token_count` timeline is
     * left unchanged.
     */
    public suspend fun updateSetting(
        settings: CodexAgentSettings,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int
}
