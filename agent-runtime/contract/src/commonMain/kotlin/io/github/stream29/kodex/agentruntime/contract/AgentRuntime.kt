package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thrown when a second collector attempts to resume an already running AgentRuntime. */
public class ConcurrentAgentRuntimeResumeException : IllegalStateException(
    "Concurrent AgentRuntime.resume() is not allowed.",
)

/**
 * A fully composed Agent runtime with externally controllable turn steering.
 *
 * @property pendingSteer Pending clean input for the current logical turn. An
 * empty list means that no steer is waiting.
 * @property runningTurn The Job currently collecting [resume], or `null` when
 * this runtime has no active turn. This is distinct from the owning Session's
 * lifecycle Job.
 * @property unifiedExecToolClient The session-scoped client shared by this
 * runtime's `exec_command` and `write_stdin` tools.
 */
public interface AgentRuntime : ResumableAgentLayer {
    public val pendingSteer: MutableStateFlow<List<StableCleanEvent.Steerable>>

    public val runningTurn: StateFlow<Job?>

    public val unifiedExecToolClient: UnifiedExecToolClient
}
