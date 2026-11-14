package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Thrown when resume or explicit compaction attempts to enter an occupied runtime turn. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime execution is not allowed.",
)

/**
 * Transient stop event emitted by [AgentRuntime.stopEvents] when an accepted outer
 * [AgentRuntime.resume] turn stops at an assistant message, host-managed pending tool calls,
 * or an unhandled non-cancellation failure.
 *
 * Cancellations, standalone [AgentRuntime.compact] calls, pre-admission rejections, and
 * `PendingAwaitEventToolEvent` parked waits do not emit stop events.
 */
public sealed interface AgentStopEvent {
    /** The assistant message produced at the end of this turn. */
    public data class AssistantMessage(
        public val message: StableAssistantMessage,
    ) : AgentStopEvent

    /** Pending `request_user_input` calls awaiting host completion at this stop, in original order. */
    public data class RequestUserInput(
        public val requests: List<PendingRequestUserInputToolEvent>,
    ) : AgentStopEvent {
        init {
            require(requests.isNotEmpty()) { "RequestUserInput stop event requires pending calls." }
        }
    }

    /** Pending `suggest_subagent_task` calls awaiting host completion at this stop, in original order. */
    public data class SuggestSubagent(
        public val requests: List<PendingSuggestSubagentTaskToolEvent>,
    ) : AgentStopEvent {
        init {
            require(requests.isNotEmpty()) { "SuggestSubagent stop event requires pending calls." }
        }
    }

    /** Non-cancellation failure that terminated an accepted [AgentRuntime.resume] turn. */
    public data class UnhandledError(
        public val message: String?,
    ) : AgentStopEvent
}

/**
 * A fully composed Agent runtime for one Session.
 *
 * It retains the [ResumableAgentLayer] state operations (including [steer] and
 * [consumeSteer] delegated to [storage]) while coordinating one outer turn at
 * a time. Responding runtime layers observe [latestIndex] and [storage] to
 * preempt in-flight model requests when steer is queued and call [consumeSteer]
 * only at legal insertion boundaries. The unified-exec client belongs to the
 * Session and is shared by both shell tools and observers.
 *
 * @property runningTurn The calling Job currently executing [resume] or explicit
 * [compact], or `null` when this runtime has no active turn. Automatic compaction
 * stays inside its existing resume turn. The slot remains occupied through cleanup
 * and is distinct from the owning Session's lifecycle Job. Recording the calling
 * Job does not itself detach execution from the caller's lifetime.
 * @property stopEvents Non-backpressuring stream (`replay = 0`, bounded `DROP_OLDEST` buffer)
 * of [AgentStopEvent]s emitted by this runtime whenever an accepted [resume] turn stops.
 * @property unifiedExecToolClient The session-scoped [UnifiedExecClient] shared
 * by this runtime's terminal tools.
 */
public interface AgentRuntime : ResumableAgentLayer {
    /**
     * Runs one outer turn through the composed runtime layers.
     *
     * `PendingAwaitEventToolEvent` is resolved entirely within this [runningTurn] slot:
     * - When `SteerSnapshot` is non-empty at a pending `await_event` boundary, completes
     *   `await_event` with `AwaitEventOutput` and continues the turn to consume the steer.
     * - Otherwise validates `terminals` against [unifiedExecToolClient] (failing unknown or
     *   evicted IDs immediately and continuing), steers `StableTerminalExitedEvent` for any
     *   already-completed watched terminals (completing `await_event` and continuing), or
     *   returns normally when all watched terminals are still running so [runningTurn]
     *   becomes `null` (external wakeups and watched-terminal exits enter via `AgentEventBus`).
     *
     * Emits the corresponding [AgentStopEvent] onto [stopEvents] when an accepted turn finishes
     * at an assistant message, host-managed pending tool calls, or an unhandled failure.
     *
     * The call returns `Unit`; callers inspect the inherited observable state
     * for its result. On cancellation, pending-tool cleanup is attempted
     * before the turn slot is released. A cleanup failure is attached to the
     * original cancellation as a suppressed exception, not substituted for it.
     *
     * @throws ConcurrentAgentRuntimeResumeException if another resume or
     * explicit compaction currently owns this runtime's turn slot.
     * @throws kotlinx.coroutines.CancellationException when execution is cancelled;
     * any failed pending-tool cleanup remains available as a suppressed exception.
     */
    public override suspend fun resume()

    /**
     * Runs explicit compaction within the same turn slot used by [resume].
     * Unlike a cancelled resume, this does not clear pending tool calls,
     * consume pending steer, emit [stopEvents], or start another resume.
     *
     * @throws ConcurrentAgentRuntimeResumeException if another resume or
     * explicit compaction currently owns this runtime's turn slot.
     */
    public override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int

    /**
     * Synchronously persists [events] into `SteerSnapshot` via [steer] (no-op when empty) and
     * coordinates turn execution with [runningTurn]:
     * - When [runningTurn] is active, the running turn observes [latestIndex], preempts any
     *   in-flight model stream, and consumes the steer via [consumeSteer] at the next safe boundary
     *   (and if the active [resume] turn was just exiting before releasing [runningTurn], the
     *   runtime re-checks wakeable `SteerSnapshot` on slot release so no submission is stranded).
     * - When [runningTurn] is idle (`null`), launches [resume] asynchronously inside this
     *   [AgentRuntime]'s own [kotlinx.coroutines.CoroutineScope] (`UNDISPATCHED`) so [runningTurn]
     *   is occupied before [submit] returns.
     */
    public suspend fun submit(events: List<StableIndexEvent.Steerable>)

    /**
     * Synchronously commits first-time [response] for [askId] via [answerAskUser]
     * (`AskUserSnapshot` + `SteerSnapshot` at one storage index) and ensures [resume] is running
     * under the same [runningTurn] handoff guarantee as [submit].
     */
    public suspend fun submitAskUserAnswer(
        askId: Int,
        response: RequestUserInputResponse,
    )

    public val runningTurn: StateFlow<Job?>

    public val stopEvents: Flow<AgentStopEvent>

    public val unifiedExecToolClient: UnifiedExecClient
}

/**
 * Convenience wrapper around [AgentRuntime.submit] for a single user message.
 */
public suspend fun AgentRuntime.submitUserMessage(content: List<ContentItem>) {
    if (content.isEmpty()) return
    submit(listOf(StableUserMessage(content)))
}
