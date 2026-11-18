package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Thrown when resume or explicit compaction attempts to enter an occupied runtime turn. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime execution is not allowed.",
)

/** Identity of an armed timeout in the latest persisted unstable snapshot. */
public data class PendingAwaitDeadline(
    public val snapshotIndex: Int,
    public val callId: String,
    public val timeoutAt: Instant,
)

/**
 * Transient stop event emitted by [AgentRuntime.stopEvents] when an accepted outer
 * [AgentRuntime.resume] turn stops at an assistant message or an unhandled non-cancellation failure.
 *
 * Cancellations, standalone [AgentRuntime.compact] calls, pre-admission rejections, and
 * `PendingAwait` parked waits do not emit stop events.
 */
public sealed interface AgentStopEvent {
    /** The assistant message produced at the end of this turn. */
    public data class AssistantMessage(
        public val message: StableAssistantMessage,
    ) : AgentStopEvent

    /** Non-cancellation failure that terminated an accepted [AgentRuntime.resume] turn. */
    public data class UnhandledError(
        public val message: String?,
    ) : AgentStopEvent
}

/**
 * A fully composed Agent runtime for one Session.
 *
 * It retains the [KodexAgentState] state operations (including [steer] and
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
public interface AgentRuntime : KodexAgentState {
    /**
     * Runs one outer turn through the composed runtime layers.
     *
     * `PendingAwait` and any remaining unowned `PendingToolCall` items are
     * resolved within this [runningTurn] slot after inner `KodexToolRuntime` handling:
     * - Any non-`await_event` pending tool call left unmatched by `KodexToolRuntime` (such as an
     *   unknown or retired tool name emitted by the model) is completed as a failed tool output
     *   so the turn can continue instead of stalling in `ToolPending`.
     * - When `SteerSnapshot` is non-empty at a pending `await_event` boundary, completes
     *   `await_event` with `AwaitEventOutput` and continues the turn to consume the steer.
     * - Otherwise validates watched terminals, handles terminal exits and due timeouts, and rejects
     *   a still-running terminal as the sole condition before parking. Accepted timeouts are armed
     *   in storage before [runningTurn] is released.
     *
     * Emits the corresponding [AgentStopEvent] onto [stopEvents] when an accepted turn finishes
     * at an assistant message or an unhandled failure.
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
    public suspend fun resume()

    /**
     * Same turn execution as [resume], signalling this invocation's admission before sampling.
     * @throws AgentResumeNotAdmittedException when state does not admit a new turn.
     */
    public suspend fun resume(onAccepted: () -> Unit)

    /** Ephemeral identity of this owner, injected into the shells launched by it. */
    public val invocationId: String

    /** Rechecks a due, still-current deadline. The returned job belongs to this Runtime. */
    public fun requestAwaitDeadlineCheck(expected: PendingAwaitDeadline): Job

    /** Rechecks the watched terminal for the same pending call; never submits stale steer. */
    public fun requestAwaitTerminalCheck(expected: PendingAwait, terminalId: Int): Job

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
    public suspend fun submit(events: List<AgentCommit.Steerable>)

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

public class AgentResumeNotAdmittedException : IllegalStateException("The Agent cannot resume in its current state.")

/**
 * Convenience wrapper around [AgentRuntime.submit] for a single user message.
 */
public suspend fun AgentRuntime.submitUserMessage(content: List<ContentItem>) {
    if (content.isEmpty()) return
    submit(listOf(StableUserMessage(content)))
}
