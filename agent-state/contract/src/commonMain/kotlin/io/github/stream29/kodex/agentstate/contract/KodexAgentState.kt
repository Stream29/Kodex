package io.github.stream29.kodex.agentstate.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable agent-state value.
 *
 * Stable values describe which next atomic operation is legal. Transient
 * values reserve state ownership while an atomic operation is in flight.
 */
public sealed interface KodexAgentStateValue {
    /** The storage contains no conversation item that can start a request. */
    public data object Empty : KodexAgentStateValue

    /** The latest conversation action is a user message. */
    public data object UserMessage : KodexAgentStateValue

    /** The latest completed conversation action is an assistant message. */
    public data object AssistantMessage : KodexAgentStateValue

    /**
     * The model emitted tool calls whose outputs have not all been persisted.
     *
     * @property events Pending locally executable clean events, including
     * client tool-search calls.
     */
    public data class ToolPending(
        public val events: List<PendingToolEvent>,
    ) : KodexAgentStateValue {
        init {
            require(events.isNotEmpty()) {
                "ToolPending requires at least one pending local tool event."
            }
        }
    }

    /** All tool outputs for the preceding tool-call batch have been persisted. */
    public data object ToolCompleted : KodexAgentStateValue

    /** A caller-initiated storage update is in flight. */
    public data object ExternalWrite : KodexAgentStateValue

    /** A single Responses API request is in flight. */
    public sealed interface RequestResponse : KodexAgentStateValue {
        /** The request has started but has no active output item. */
        public data object Started : RequestResponse

        /** A currently streaming message output item. */
        public data class Message(
            public val events: SharedFlow<ResponsesStreamEvent>,
        ) : RequestResponse

        /** A currently streaming inter-Agent message output item. */
        public data class AgentMessage(
            public val events: SharedFlow<ResponsesStreamEvent>,
        ) : RequestResponse

        /** A currently streaming reasoning output item. */
        public data class Reasoning(
            public val events: SharedFlow<ResponsesStreamEvent>,
        ) : RequestResponse

        /** A currently streaming tool-call output item of any tool kind. */
        public data class ToolCall(
            public val events: SharedFlow<ResponsesStreamEvent>,
        ) : RequestResponse

        /** A currently streaming protocol item without a modeled semantic kind. */
        public data class Unknown(
            public val events: SharedFlow<ResponsesStreamEvent>,
        ) : RequestResponse
    }

    /** A single server-side context compaction request is in flight. */
    public data object Compacting : KodexAgentStateValue
}

/** Whether appending a user message is a legal next atomic operation. */
public val KodexAgentStateValue.canAppendUserMessage: Boolean
    get() = this == KodexAgentStateValue.Empty ||
        this == KodexAgentStateValue.UserMessage ||
        this == KodexAgentStateValue.AssistantMessage ||
        this == KodexAgentStateValue.ToolCompleted

/** Whether marking the start of a new logical turn is legal. */
public val KodexAgentStateValue.canMarkNewTurn: Boolean
    get() = canAppendUserMessage

/** Whether requesting a Responses API continuation is legal. */
public val KodexAgentStateValue.canRequestResponseApi: Boolean
    get() = this == KodexAgentStateValue.UserMessage ||
        this == KodexAgentStateValue.AssistantMessage ||
        this == KodexAgentStateValue.ToolCompleted

/** Whether compacting the current model context is legal. */
public val KodexAgentStateValue.canCompact: Boolean
    get() = this == KodexAgentStateValue.UserMessage ||
        this == KodexAgentStateValue.AssistantMessage ||
        this == KodexAgentStateValue.ToolCompleted

/**
 * Observable atomic agent state.
 *
 * One state operates on exactly one AgentStorage. Session trees, parent-child
 * relationships, cross-Agent messages, and Agent scheduling belong to the
 * ordinary Multi-agent tools installed by the runtime layer.
 *
 * This interface intentionally contains both observation and state-transition
 * operations while exposing [storage] only as read-only data.
 *
 * Its [CoroutineScope] is the lifecycle root for every runtime decorator and
 * background task belonging to this Agent. Implementations must attach it as
 * a child of the owning AgentSession scope so Session cancellation propagates
 * through the complete State and Runtime chain.
 *
 * Implementations commit each storage transition before publishing its next
 * stable [state]. They publish [KodexAgentStorage.tokenCount] only when OpenAI reports it.
 */
public interface KodexAgentState : CoroutineScope {
    /**
     * Current atomic state value.
     */
    public val state: StateFlow<KodexAgentStateValue>

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
    public val storage: KodexAgentStorage

    /**
     * Runs one caller-defined storage mutation as an atomic AgentState write.
     *
     * The implementation exposes mutable storage only for [block]'s lifetime.
     * After [block] exits, normally or exceptionally, it reloads [latestIndex]
     * and [state] from the actual storage contents before releasing the write.
     *
     * Storage-level operations such as initialization, fork, and revert remain
     * defined by the AgentStorage contract and should be invoked through this
     * boundary when the storage belongs to a live AgentState.
     */
    public suspend fun <T> modify(
        block: suspend (MutableKodexAgentStorage) -> T,
    ): T

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
     * Injects model-visible stable clean events without reopening a generic
     * storage write API.
     *
     * A user message in [events] remains part of the current logical turn and
     * does not rotate the persisted turn id. The item role alone does not
     * define a turn boundary.
     *
     * An empty [events] list is a no-op and returns the current visible index.
     * Non-empty lists are persisted on the stable timeline as one atomic state
     * transition in the supplied order. Pending calls must enter through a
     * model response, and pending completion must use [completeToolCall].
     */
    public suspend fun injectHistory(events: List<StableCleanEvent>): Int

    /**
     * Marks the next user message as the start of a new logical turn.
     *
     * An empty agent already owns the initial turn id, so this is a no-op in
     * [KodexAgentStateValue.Empty]. Other legal states atomically persist a new
     * UUIDv7 turn id without changing conversation state.
     */
    public suspend fun markNewTurn(): Int

    /**
     * Appends one user message without changing the persisted turn id.
     *
     * A formal user submission starts with [markNewTurn], while a runtime
     * inserting user input into the current turn calls this operation directly.
     * A user-role context injection must use [injectHistory].
     */
    public suspend fun appendUserMessage(content: List<ContentItem>): Int

    /**
     * Completes one currently pending local tool call, including a
     * client-executed tool-search call.
     *
     * The event's projected output call id must match a pending clean call.
     * The same transition persists [completed] on the stable timeline and
     * removes the matching call id from the unstable pending snapshot. State
     * remains ToolPending until every call from the current batch has an
     * output.
     */
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int

    /**
     * Persists one parsed `update_plan` result and updates the settings
     * snapshot's plan atomically.
     *
     * [completed] must match a pending function call named `update_plan`. Tool
     * dispatch selects this operation explicitly rather than relying on
     * [completeToolCall] to inspect tool-specific payloads. Plan mode rejects
     * this operation because Codex does not expose `update_plan` in that mode.
     */
    public suspend fun appendPlanUpdate(completed: StablePlanUpdate): Int

    /**
     * Updates model request settings and records a timestamp for the same state
     * transition. This does not change the conversation state.
     *
     * OpenAI-reported token counts are written only by completed model or
     * compaction requests, so callers cannot supply an arbitrary value here.
     */
    public suspend fun updateSettings(settings: KodexAgentSettings): Int

}
