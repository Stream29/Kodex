package io.github.stream29.kodex.agentstate.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.OpenAiResponseStreamIncompleteException
import io.github.stream29.kodex.agentstate.impl.KodexAgentStateInvalidTransitionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
     * @throws IllegalArgumentException when [events] is empty.
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
 * One state operates on exactly one AgentStorage. Session registries, Agent
 * scheduling, and multi-step tool execution belong to their owning application
 * and runtime layers, not to this atomic operation interface.
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
 * stable [state]. Ordinary responses publish [KodexAgentStorage.tokenCount]
 * only when OpenAI reports it; every successful compaction publishes a
 * synthetic `0` for the new context window.
 *
 * Every operation admission and storage commit shares one fair per-instance
 * write queue. A model request retains logical ownership through its published
 * [KodexAgentStateValue.RequestResponse] value while releasing that queue
 * between storage commits.
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
     *
     * [block] already owns the non-reentrant AgentState write lock and must not
     * invoke another mutation operation on this AgentState.
     * Committed changes are not rolled back; storage and block failures
     * propagate after observable-state recovery.
     *
     * @throws KodexAgentStateInvalidTransitionException when an in-flight
     * request, compaction, or external write already owns the state.
     * @throws CancellationException when admission or execution is cancelled;
     * final state recovery is non-cancellable.
     */
    public suspend fun <T> modify(
        block: suspend (MutableKodexAgentStorage) -> T,
    ): T

    /**
     * Executes exactly one model request from the current state, commits each
     * completed output item, and returns that request's normal completion
     * disposition. Completed responses map `end_turn == false` to Continue
     * and other completed responses to Finish. Failed terminal responses and
     * streams without a terminal event return Retryable; incomplete responses
     * raise an exception retaining their available protocol diagnostics.
     *
     * The implementation passes the settings visible at the request snapshot
     * to its bound context-prefix provider, renders the result, then prepends
     * that prefix to persisted model input without writing it to storage or
     * compaction history. It also derives the complete model-visible tool list
     * from fixed Codex tools, current settings, and its dynamic tool-search
     * source.
     *
     * The operation atomically claims [KodexAgentStateValue.RequestResponse]
     * and captures one storage snapshot, then releases the write queue while
     * it consumes the remote stream. Active output events are published through
     * that state value's [SharedFlow], not returned from this operation. Each
     * completed output commit and final state recovery reacquires the queue.
     * Conflicting state transitions are invalid while the request value remains
     * published; settings writes may proceed and affect the next request.
     *
     * Automatic compaction and `end_turn == false` continuation belong to
     * AgentRuntime rather than this state-layer operation.
     * Transport, context-loading, and storage failures propagate; already
     * committed output items remain persisted after failure or cancellation.
     *
     * @throws KodexAgentStateInvalidTransitionException when state is not
     * requestable or the request loses ownership before a commit.
     * @throws OpenAiResponseStreamIncompleteException for an incomplete terminal.
     * @throws IllegalStateException when initial storage is absent, response
     * turn-state persistence fails, or a hosted tool-search output is missing.
     * @throws CancellationException when the caller cancels the request.
     */
    public suspend fun requestResponseApi(): RequestFinish

    /**
     * Requests one server-side context compaction using the specified runtime
     * policy metadata and returns the index that publishes its checkpoint.
     *
     * The request and committed checkpoint retain the current persisted turn
     * identity. Runtime owns the decision to call this operation automatically.
     * Compaction retains logical ownership while releasing the write queue
     * during its remote wait. Checkpoint creation currently uses the captured
     * settings; concurrent settings updates are not guaranteed to survive it.
     * This limitation is tracked separately from module migration.
     * Delegate failures propagate; final state recovery is non-cancellable.
     *
     * @throws KodexAgentStateInvalidTransitionException when compaction is not legal.
     * @throws IllegalStateException when initial storage is absent or compaction
     * ownership is lost before checkpoint commit.
     * @throws CancellationException when the caller cancels the operation.
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
     * User messages apply the same historical turn-boundary inference as
     * [appendUserMessage]: a preceding non-commentary assistant message starts
     * a new turn; consecutive user messages and assistant commentary do not.
     *
     * An empty [events] list is a no-op and returns the current visible index.
     * Non-empty lists are persisted on the stable timeline as one atomic state
     * transition in the supplied order. Pending calls must enter through a
     * model response, and pending completion must use [completeToolCall].
     * Storage failures propagate; committed prefixes are not rolled back.
     *
     * @throws KodexAgentStateInvalidTransitionException when a non-empty
     * injection conflicts with an in-flight atomic operation.
     * @throws CancellationException when admission or writing is cancelled.
     */
    public suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int

    /**
     * Appends one user message and updates the settings turn id when it starts
     * a new logical turn.
     *
     * A user message following an assistant commentary message continues the
     * current turn. A user message following an assistant final message starts
     * a new turn. A user-role context injection must use [injectHistory].
     * Storage failures propagate without compensating committed writes.
     *
     * @throws KodexAgentStateInvalidTransitionException when user append is not legal.
     * @throws CancellationException when admission or writing is cancelled.
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
     * Storage failures propagate without compensating committed writes.
     *
     * @throws KodexAgentStateInvalidTransitionException unless state is ToolPending.
     * @throws IllegalArgumentException when the projected output has no matching
     * pending call id or the unstable snapshot lacks that call.
     * @throws CancellationException when admission or writing is cancelled.
     */
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int

    /**
     * Updates model request settings and records a timestamp for the same state
     * transition. This does not change the conversation state.
     *
     * OpenAI-reported token counts are written only by completed model or
     * compaction requests, so callers cannot supply an arbitrary value here.
     * This may run during a request or compaction without changing captured
     * inputs. Storage failures propagate, including partial writes.
     *
     * @throws IllegalArgumentException when no initial storage index exists.
     * @throws CancellationException when admission or writing is cancelled.
     */
    public suspend fun updateSettings(settings: KodexAgentSettings): Int

    /**
     * Compares the complete current settings and appends [update] under the
     * same write boundary as other state mutations, including response headers.
     *
     * A mismatch returns false; an equal update succeeds without appending
     * settings or a timestamp. Like [updateSettings], this may run during a
     * model request without changing its captured settings or conversation
     * state. Storage failures and cancellation propagate to the caller.
     *
     * @throws IllegalArgumentException when a distinct update would append
     * without an initial storage index.
     * @throws CancellationException when admission or writing is cancelled.
     */
    public suspend fun compareAndSetSettings(
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean
}
