package io.github.stream29.codex.lite.agentruntime.contract

import io.github.stream29.codex.lite.openai.ResponseItem
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
 * @property pendingSteer Pending typed input for the current logical turn. An
 * empty list means that no steer is waiting.
 * @property runningTurn The Job currently collecting [resume], or `null` when
 * this runtime has no active turn. This is distinct from the owning Session's
 * lifecycle Job.
 */
public interface AgentRuntime : ResumableAgentLayer {
    public val pendingSteer: MutableStateFlow<List<ResponseItem.Steerable>>

    public val runningTurn: StateFlow<Job?>
}
