package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thrown when resume or explicit compaction attempts to enter an occupied runtime turn. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime execution is not allowed.",
)

/**
 * A fully composed Agent runtime for one Session.
 *
 * It retains the [ResumableAgentLayer] state operations while coordinating
 * one outer turn at a time. Pending steer is host-visible input for the
 * current logical turn; a composed steer layer claims it only when the state
 * permits another user message. The unified-exec client belongs to the
 * Session and is shared by both shell tools and observers.
 *
 * @property pendingSteer Pending clean input for the current logical turn. An
 * empty list means that no steer is waiting.
 * @property runningTurn The calling Job currently executing [resume] or explicit
 * [compact], or `null` when this runtime has no active turn. Automatic compaction
 * stays inside its existing resume turn. The slot remains occupied through cleanup
 * and is distinct from the owning Session's lifecycle Job. Recording the calling
 * Job does not itself detach execution from the caller's lifetime.
 * @property unifiedExecToolClient The session-scoped [UnifiedExecClient] shared
 * by this runtime's `exec_command` and `write_stdin` tools.
 */
public interface AgentRuntime : ResumableAgentLayer {
    /**
     * Runs one outer turn through the composed runtime layers.
     *
     * The call returns `Unit`; callers inspect the inherited observable state
     * for its result. On cancellation, pending-tool cleanup is attempted
     * before the turn slot is released.
     *
     * @throws ConcurrentAgentRuntimeResumeException if another resume or
     * explicit compaction currently owns this runtime's turn slot.
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

    public val pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>

    public val runningTurn: StateFlow<Job?>

    public val unifiedExecToolClient: UnifiedExecClient
}
