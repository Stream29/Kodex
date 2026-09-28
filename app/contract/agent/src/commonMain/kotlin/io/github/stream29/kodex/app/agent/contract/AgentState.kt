package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.StateFlow

/** Observable process handle safe for frontend presentation and cancellation. */
public interface AgentShellSession : AutoCloseable {
    public val sessionId: Int
    public val arguments: ExecCommandArguments
    public val completed: StateFlow<Boolean>

    override fun close(): Unit
}

/**
 * Stable handle exposing only the process sessions owned by one Agent.
 *
 * The underlying shell client and its execution methods remain private.
 */
public interface AgentShellSessionRegistry {
    /**
     * Raw active-session registry, including completed sessions retained by the
     * execution layer for final output reads.
     */
    public val activeSessions: StateFlow<Map<Int, AgentShellSession>>
}

/** Agent-owned confirmation state for a destructive history revert. */
public sealed interface AgentHistoryActionState {
    public data object None : AgentHistoryActionState

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

/** Latest Agent-scoped operation result, isolated from other Agents. */
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

/** UI-facing lifetime of one materialized Agent ViewModel. */
public sealed interface AgentLifecycleState {
    public data object Open : AgentLifecycleState
    public data object Closing : AgentLifecycleState
    public data object Closed : AgentLifecycleState

    public data class Failed(
        public val message: String,
    ) : AgentLifecycleState {
        init {
            require(message.isNotBlank()) { "An Agent lifecycle failure message must not be blank." }
        }
    }
}
