package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.StateFlow

/** Lightweight execution phase without a streaming payload or runtime handle. */
public enum class AgentExecutionPhase {
    Empty,
    UserMessage,
    Responding,
    AssistantMessage,
    ToolPending,
    ToolCompleted,
    ExternalWrite,
    Compacting,
}

/** Commands currently admissible for one exact Agent snapshot. */
public data class AgentExecutionCapabilities(
    public val canSubmit: Boolean = false,
    public val canResume: Boolean = false,
    public val canCancel: Boolean = false,
    public val canClearPending: Boolean = false,
    public val canCompact: Boolean = false,
    public val canReplaceHistory: Boolean = false,
    public val canForkHistory: Boolean = false,
)

/**
 * Low-frequency execution facts consumed by controls and lightweight summaries.
 *
 * Stream events, pending steer content, settings, tokens, and failures
 * are intentionally absent.
 */
public data class AgentExecutionState(
    public val phase: AgentExecutionPhase = AgentExecutionPhase.Empty,
    public val running: Boolean = false,
    public val latestStorageIndex: Int = -1,
    public val activityVersion: Long = 0,
    public val capabilities: AgentExecutionCapabilities = AgentExecutionCapabilities(),
) {
    init {
        require(latestStorageIndex >= -1) {
            "An Agent latest storage index must be -1 or non-negative."
        }
        require(activityVersion >= 0) { "An Agent activity version must not be negative." }
    }
}

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

/** One committed history boundary selected for revert or fork. */
public data class AgentHistoryTarget(
    public val generation: Long,
    public val storageIndex: Int,
) {
    init {
        require(generation >= 0) { "A history target generation must not be negative." }
        require(storageIndex in 0 until Int.MAX_VALUE) {
            "A history target must have a non-negative finite successor."
        }
    }

    public val untilExclusive: Int = storageIndex + 1
}

/** Agent-owned confirmation state for a destructive history revert. */
public sealed interface AgentHistoryActionState {
    public data object None : AgentHistoryActionState

    public data class ConfirmRevert(
        public val requestId: Long,
        public val target: AgentHistoryTarget,
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
