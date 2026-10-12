package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable process handle safe for frontend presentation and cancellation.
 *
 * Borrowed from the exact Agent registry; unmounting a row/hover/menu does not close it.
 * Render [arguments] without consuming process output. [completed] hides an ongoing row
 * and dismisses its menu, but does not remove the raw registry entry.
 */
public interface AgentShellSession : AutoCloseable {
    public val sessionId: Int
    public val arguments: ExecCommandArguments
    public val completed: StateFlow<Boolean>

    /** Explicitly closes this captured process, never a newly selected Agent's process. */
    override fun close(): Unit
}

/**
 * Stable handle exposing only the process sessions owned by one Agent.
 *
 * The underlying shell client and its execution methods remain private.
 * History and both sidebars borrow this same handle; each renderer may keep its own
 * focus/scroll/hover state, not another process registry. Ongoing lists filter completed
 * entries and sort by [AgentShellSession.sessionId].
 */
public interface AgentShellSessionRegistry {
    /**
     * Raw active-session registry, including completed sessions retained by the
     * execution layer for final output reads.
     */
    public val activeSessions: StateFlow<Map<Int, AgentShellSession>>
}

/**
 * Agent-owned confirmation state for a destructive history revert.
 *
 * Render no confirmation for [None]; [ConfirmRevert] binds its exact request id,
 * exclusive boundary and generation. A callback must not reconstruct those from
 * a newly selected row/Agent.
 */
public sealed interface AgentHistoryActionState {
    public data object None : AgentHistoryActionState

    /**
     * @throws IllegalArgumentException when [requestId] is not positive.
     */
    public data class ConfirmRevert(
        public val requestId: Long,
        public val untilExclusive: Int,
        public val expectedGeneration: Long,
    ) : AgentHistoryActionState {
        init {
            require(requestId > 0) { "An Agent history request id must be positive." }
        }
    }
}

public enum class AgentNotificationLevel {
    Information,
    Warning,
    Error,
}

/**
 * Latest Agent-scoped operation result, isolated from other Agents.
 *
 * Render the message and optional detail once at the Agent host. Composer retains
 * its typed submission state; that is not a second notification to render alongside it.
 *
 * @throws IllegalArgumentException when [id] is not positive or [message] is blank.
 */
public data class AgentNotification(
    public val id: Long,
    public val level: AgentNotificationLevel,
    public val message: String,
    public val detail: String? = null,
) {
    init {
        require(id > 0) { "An Agent notification id must be positive." }
        require(message.isNotBlank()) { "An Agent notification message must not be blank." }
    }
}

/**
 * UI-facing lifetime of one materialized Agent ViewModel.
 *
 * [Open] permits commands subject to backend admission; [Closing]/[Closed] are not
 * runnable. [Failed] exposes the failure message, not an empty/idle Agent. Binding
 * replacement publishes a different owner; a renderer must not retarget old callbacks.
 */
public sealed interface AgentLifecycleState {
    public data object Open : AgentLifecycleState
    public data object Closing : AgentLifecycleState
    public data object Closed : AgentLifecycleState

    /**
     * @throws IllegalArgumentException when [message] is blank.
     */
    public data class Failed(
        public val message: String,
    ) : AgentLifecycleState {
        init {
            require(message.isNotBlank()) { "An Agent lifecycle failure message must not be blank." }
        }
    }
}
