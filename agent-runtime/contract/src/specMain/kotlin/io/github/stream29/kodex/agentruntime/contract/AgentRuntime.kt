package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** Thrown when resume or explicit compaction attempts to enter an occupied runtime turn. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime execution is not allowed.",
)

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
 * @property unifiedExecToolClient The session-scoped [UnifiedExecClient] shared
 * by this runtime's terminal tools.
 */
public interface AgentRuntime : ResumableAgentLayer {
    /**
     * Runs one outer turn through the composed runtime layers.
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
     * consume pending steer, or start another resume.
     *
     * @throws ConcurrentAgentRuntimeResumeException if another resume or
     * explicit compaction currently owns this runtime's turn slot.
     */
    public override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int

    public val runningTurn: StateFlow<Job?>

    public val unifiedExecToolClient: UnifiedExecClient
}

/**
 * Unified input submission extension over the Agent state machine and runtime.
 *
 * Persists [events] into `SteerSnapshot` via [AgentRuntime.steer].
 * When [AgentRuntime.runningTurn] is currently occupied, the active turn
 * observes the storage update via [AgentRuntime.latestIndex], preempts any
 * in-flight model stream, and consumes the queued steer via
 * [AgentRuntime.consumeSteer] at the next legal boundary (`steer` only).
 * When [AgentRuntime.runningTurn] is idle (`null`), this extension starts
 * [AgentRuntime.resume] (`steer + resume`), which consumes the persisted steer
 * at turn entry and runs the turn to completion. If another caller claims
 * [AgentRuntime.runningTurn] concurrently after [AgentRuntime.steer] commits,
 * that active turn will consume the persisted steer so
 * [ConcurrentAgentRuntimeResumeException] is treated as benign handoff.
 */
public expect suspend fun AgentRuntime.submit(events: List<StableIndexEvent.Steerable>)

/**
 * Convenience wrapper around [submit] for a single user message.
 */
public suspend fun AgentRuntime.submitUserMessage(content: List<ContentItem>) {
    if (content.isEmpty()) return
    submit(listOf(StableUserMessage(content)))
}
