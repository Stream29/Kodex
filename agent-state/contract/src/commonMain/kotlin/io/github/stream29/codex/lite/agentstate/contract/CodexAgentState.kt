package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.CompactionTrigger
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable agent-state value.
 *
 * Stable values describe which next atomic operation is legal. Transient
 * values reserve state ownership while an atomic operation is in flight.
 */
public sealed interface CodexAgentStateValue {
    /** The storage contains no conversation item that can start a request. */
    public data object Empty : CodexAgentStateValue

    /** The latest conversation action is a user message. */
    public data object UserMessage : CodexAgentStateValue

    /** The latest completed conversation action is an assistant message. */
    public data object AssistantMessage : CodexAgentStateValue

    /**
     * The model emitted tool calls whose outputs have not all been persisted.
     *
     * @property calls Pending locally executable calls, including client tool
     * search calls.
     */
    public data class ToolPending(
        public val calls: List<ResponseItem.ToolCall>,
    ) : CodexAgentStateValue {
        init {
            require(calls.isNotEmpty()) {
                "ToolPending requires at least one pending local tool call."
            }
        }
    }

    /** All tool outputs for the preceding tool-call batch have been persisted. */
    public data object ToolCompleted : CodexAgentStateValue

    /** A caller-initiated storage update is in flight. */
    public data object ExternalWrite : CodexAgentStateValue

    /** A single Responses API request is in flight. */
    public data object RequestResponse : CodexAgentStateValue

    /** A single server-side context compaction request is in flight. */
    public data object Compacting : CodexAgentStateValue
}

/**
 * Observable atomic agent state.
 *
 * One state operates on exactly one AgentStorage. Session trees, parent-child
 * relationships, cross-Agent messages, and Agent scheduling belong to the
 * multi-Agent coordinator above this interface.
 *
 * This interface intentionally contains both observation and state-transition
 * operations while exposing [storage] only as read-only data.
 *
 * Implementations commit each storage transition before publishing its next
 * stable [state]. They publish [CodexAgentStorage.tokenCount] only when OpenAI reports it.
 */
public interface CodexAgentState {
    /**
     * Current atomic state value.
     */
    public val state: StateFlow<CodexAgentStateValue>

    /**
     * Latest globally visible storage snapshot index.
     * When updating [storage], this value will be updated only after the
     * transaction completes to keep the consistent snapshot semantic.
     *
     * Readers should capture this value once and use it to read [storage].
     */
    public val latestIndex: StateFlow<Int>

    /**
     * Read-only persisted agent data.
     */
    public val storage: CodexAgentStorage

    /**
     * Executes exactly one model request from the current state, commits each
     * completed output item, and returns that request's raw stream events.
     *
     * The implementation passes the settings visible at the request snapshot
     * to its bound context-prefix provider, renders the result, then prepends
     * that prefix to persisted model input without writing it to storage or
     * compaction history. It also derives the complete model-visible tool list
     * from fixed Codex tools, current settings, and its dynamic tool-search
     * source.
     *
     * Automatic compaction and `end_turn == false` continuation belong to
     * AgentRuntime rather than this state-layer operation.
     */
    public fun requestResponseApi(): Flow<ResponsesStreamEvent>

    /**
     * Requests one server-side context compaction using the specified runtime
     * policy metadata and returns the index that publishes its checkpoint.
     *
     * The request and committed checkpoint retain the current persisted turn
     * identity. Runtime owns the decision to call this operation automatically.
     */
    public suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int

    /**
     * Injects model-visible host history without reopening a generic history
     * write API.
     *
     * A user-role item in [items] remains part of the current logical turn and
     * does not rotate the persisted turn id. The item role alone does not
     * define a turn boundary.
     *
     * An empty [items] list is a no-op and returns the current visible index.
     * Non-empty lists are persisted as one atomic state transition in the
     * supplied order.
     */
    public suspend fun injectHistory(items: List<ResponseItem.HistoryItem>): Int

    /**
     * Starts one logical user turn by appending its first user message.
     *
     * The first turn retains the id created with the initial settings. A later
     * call allocates a new turn id because a new user submission after the
     * preceding agent run has ended starts a new logical turn.
     *
     * A user-role context injection is not a turn boundary and must use
     * [injectHistory]. A runtime inserting user input into an already active
     * turn must retain that turn's id through the explicit overload.
     */
    public suspend fun appendUserMessage(content: List<ContentItem>): Int

    /**
     * Appends one user message and atomically persists the supplied [turnId].
     *
     * The caller owns the turn-boundary decision: a newly allocated id starts
     * a new logical turn, while the current id keeps accepted mid-turn input
     * in the active turn.
     */
    public suspend fun appendUserMessage(
        content: List<ContentItem>,
        turnId: String,
    ): Int

    /**
     * Persists one output for a currently pending local tool call, including a
     * client-executed tool-search call.
     *
     * The output call id must match a pending call. State remains ToolPending
     * until every call from the current batch has an output.
     */
    public suspend fun completeToolCall(output: ResponseItem.ToolCallOutput): Int

    /**
     * Persists one parsed `update_plan` result and updates the settings
     * snapshot's plan atomically.
     *
     * [output] must match a pending function call named `update_plan`. Tool
     * dispatch selects this operation explicitly rather than relying on
     * [completeToolCall] to inspect tool-specific payloads. Plan mode rejects
     * this operation because Codex does not expose `update_plan` in that mode.
     */
    public suspend fun appendPlanUpdate(
        output: ResponseItem.FunctionCallOutput,
        plan: UpdatePlanArgs,
    ): Int

    /**
     * Updates model request settings and records a timestamp for the same state
     * transition. This does not change the conversation state.
     *
     * OpenAI-reported token counts are written only by completed model or
     * compaction requests, so callers cannot supply an arbitrary value here.
     */
    public suspend fun updateSettings(settings: CodexAgentSettings): Int

    /**
     * Destructively reverts to the snapshot immediately before [untilExclusive].
     *
     * The target must be the initial empty snapshot or a completed assistant
     * turn. Implementations discard all later storage transitions, then publish
     * the rebuilt state and latest index together.
     */
    public suspend fun revert(untilExclusive: Int): Int
}
