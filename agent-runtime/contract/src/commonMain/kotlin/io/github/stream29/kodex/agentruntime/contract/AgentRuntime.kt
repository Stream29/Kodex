package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thrown when resume or explicit compaction attempts to enter an occupied runtime turn. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime execution is not allowed.",
)

/**
 * A fully composed Agent runtime with externally controllable turn steering.
 *
 * @property pendingSteer Pending clean input for the current logical turn. An
 * empty list means that no steer is waiting.
 * @property runningTurn The calling Job currently executing [resume] or explicit
 * [compact], or `null` when this runtime has no active turn. Automatic compaction
 * stays inside its existing resume turn. The slot remains occupied through cleanup
 * and is distinct from the owning Session's lifecycle Job. Recording the calling
 * Job does not itself detach execution from the caller's lifetime.
 * @property unifiedExecToolClient The session-scoped client shared by this
 * runtime's `exec_command` and `write_stdin` tools.
 */
public interface AgentRuntime : KodexAgentState {
    /** Runs one complete runtime operation; observable state carries its result. */
    public suspend fun resume()

    public val pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>

    public val runningTurn: StateFlow<Job?>

    public val unifiedExecToolClient: UnifiedExecToolClient
}
