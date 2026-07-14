package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
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
     * [calls] is a snapshot derived from the active storage history. It lets
     * AgentState validate and complete one local tool result without rescanning
     * that history.
     */
    public data class ToolPending(
        public val calls: List<ResponseItem.ToolCall>,
    ) : CodexAgentStateValue {
        init {
            require(calls.isNotEmpty()) { "ToolPending requires at least one pending tool call." }
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
 * This interface intentionally contains both observation and state-transition
 * operations while exposing [storage] only as read-only data.
 *
 * Implementations commit each storage transition before publishing its next
 * stable [state]. They publish [tokenCount] only when OpenAI reports it.
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
     * Automatic compaction and `end_turn == false` continuation belong to
     * AgentRuntime rather than this state-layer operation.
     */
    public fun requestResponseApi(): Flow<ResponsesStreamEvent>

    /**
     * Requests one server-side context compaction using the specified runtime
     * policy metadata and returns the index that publishes its checkpoint.
     *
     * Runtime owns the decision to call this operation automatically.
     */
    public suspend fun compact(
        trigger: RemoteCompactionV2Trigger,
        reason: RemoteCompactionV2Reason,
        phase: RemoteCompactionV2Phase,
    ): Int

    /**
     * Injects model-visible host history without reopening a generic history
     * write API.
     *
     * An empty [items] list is a no-op and returns the current visible index.
     * Non-empty lists are persisted as one atomic state transition in the
     * supplied order.
     */
    public suspend fun injectHistory(items: List<ResponseItem.HistoryItem>): Int

    /**
     * Appends one user message and records its timestamp in the same state
     * transition.
     *
     * This is valid for a new conversation or after a user or assistant
     * message. Consecutive user messages are valid Responses API input.
     */
    public suspend fun appendUserMessage(content: List<ContentItem>): Int

    /**
     * Persists one output for a currently pending local tool call.
     *
     * The output call id must match a pending call. State remains ToolPending
     * until every call from the current batch has an output.
     */
    public suspend fun completeToolCall(output: ResponseItem.ToolCallOutput): Int

    /**
     * Persists one parsed `update_plan` result and plan snapshot atomically.
     *
     * [output] must match a pending function call named `update_plan`. Tool
     * dispatch selects this operation explicitly rather than relying on
     * [completeToolCall] to inspect tool-specific payloads.
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
}
