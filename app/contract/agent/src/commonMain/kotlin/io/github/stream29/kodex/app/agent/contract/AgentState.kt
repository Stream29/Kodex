package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.flow.SharedFlow
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
 * Low-frequency execution facts consumed by controls and lightweight topology.
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

/** Semantic kind of the currently streaming Responses output item. */
public enum class AgentStreamKind {
    Message,
    AgentMessage,
    Reasoning,
    ToolCall,
    Unknown,
}

/**
 * One active high-frequency operation rendered after committed history.
 *
 * An [Output] forwards the execution layer's replaying event source rather
 * than copying every delta into an aggregate ViewModel state.
 */
public sealed interface AgentStreamTail {
    public data object Started : AgentStreamTail

    public data class Output(
        public val kind: AgentStreamKind,
        public val events: SharedFlow<ResponsesStreamEvent>,
    ) : AgentStreamTail

    public data object Compacting : AgentStreamTail
}

/**
 * High-frequency transient timeline state, separate from committed history.
 */
public data class AgentStreamState(
    public val tail: AgentStreamTail? = null,
    public val pendingEvents: List<UnstableCleanEvent> = emptyList(),
    public val pendingSteer: List<StableCleanEvent.Steerable> = emptyList(),
    public val revision: Long = 0,
) {
    init {
        require(revision >= 0) { "An Agent stream revision must not be negative." }
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
 * Stable child handle exposing only the process sessions owned by one Agent.
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

/** Lightweight direct-child information that does not require a child ViewModel. */
public data class AgentChildSlot(
    public val address: AgentAddress,
    public val threadName: String? = null,
    public val phase: AgentExecutionPhase = AgentExecutionPhase.Empty,
    public val running: Boolean = false,
    public val activityVersion: Long = 0,
    public val hasChildren: Boolean = false,
) {
    init {
        require(threadName == null || threadName.isNotBlank()) {
            "An Agent child thread name must be null or non-blank."
        }
        require(activityVersion >= 0) { "An Agent child activity version must not be negative." }
    }
}

/** Direct-child discovery/materialization state for one Agent. */
public sealed interface AgentChildrenState {
    public data object Unloaded : AgentChildrenState

    public data class Loading(
        public val revision: Long,
    ) : AgentChildrenState {
        init {
            require(revision >= 0) { "An Agent children revision must not be negative." }
        }
    }

    public data class Loaded(
        public val children: List<AgentChildSlot>,
        public val revision: Long,
    ) : AgentChildrenState {
        init {
            require(revision >= 0) { "An Agent children revision must not be negative." }
            require(children.map(AgentChildSlot::address).distinct().size == children.size) {
                "Direct Agent child addresses must be unique."
            }
        }
    }

    public data class Failed(
        public val revision: Long,
        public val message: String,
    ) : AgentChildrenState {
        init {
            require(revision >= 0) { "An Agent children revision must not be negative." }
            require(message.isNotBlank()) { "An Agent children failure message must not be blank." }
        }
    }
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
