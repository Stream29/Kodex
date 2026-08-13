package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import kotlin.time.Instant

/** Root and aggregate facts used by persisted Session controls. */
public data class PersistedSessionSummaryState(
    public val rootRunning: Boolean = false,
    public val aggregateRunning: Boolean = false,
    public val lastActivityAt: Instant? = null,
    public val agentCount: Int = 1,
    public val revision: Long = 0,
) {
    init {
        require(agentCount > 0) {
            "A persisted Session must contain at least its root Agent."
        }
        require(revision >= 0) {
            "A persisted Session summary revision must not be negative."
        }
    }
}

/** Detailed ViewModel materialization status for one lightweight Agent slot. */
public sealed interface PersistedAgentMaterializationState {
    public data object Unloaded : PersistedAgentMaterializationState
    public data object Loading : PersistedAgentMaterializationState
    public data object Loaded : PersistedAgentMaterializationState

    public data class Failed(
        public val detail: String,
    ) : PersistedAgentMaterializationState {
        init {
            require(detail.isNotBlank()) {
                "An Agent materialization failure detail must not be blank."
            }
        }
    }
}

/** Lightweight topology node without a child ViewModel or copied child state. */
public data class PersistedSessionTopologyNode(
    public val address: AgentAddress,
    public val parentAddress: AgentAddress?,
    public val depth: Int,
    public val threadName: String? = null,
    public val phase: AgentExecutionPhase = AgentExecutionPhase.Empty,
    public val running: Boolean = false,
    public val activityVersion: Long = 0,
    public val hasChildren: Boolean = false,
    public val materialization: PersistedAgentMaterializationState =
        PersistedAgentMaterializationState.Unloaded,
) {
    init {
        require(depth >= 0) { "A Session topology depth must not be negative." }
        require(parentAddress == null || parentAddress.sessionIndex == address.sessionIndex) {
            "An Agent and its parent must belong to the same persisted Session."
        }
        require(threadName == null || threadName.isNotBlank()) {
            "A Session topology thread name must be null or non-blank."
        }
        require(activityVersion >= 0) {
            "A Session topology activity version must not be negative."
        }
    }
}

/** Atomic lightweight topology for children that may not be materialized. */
public data class PersistedSessionTopologyState(
    public val rootAddress: AgentAddress,
    public val nodes: List<PersistedSessionTopologyNode>,
    public val revision: Long = 0,
) {
    init {
        require(revision >= 0) {
            "A persisted Session topology revision must not be negative."
        }
        require(nodes.map(PersistedSessionTopologyNode::address).distinct().size == nodes.size) {
            "Persisted Session topology Agent addresses must be unique."
        }
        require(nodes.all { node -> node.address.sessionIndex == rootAddress.sessionIndex }) {
            "Every topology node must belong to its root persisted Session."
        }
        val nodesByAddress = nodes.associateBy(PersistedSessionTopologyNode::address)
        val root = requireNotNull(nodesByAddress[rootAddress]) {
            "A persisted Session topology must contain its root Agent."
        }
        require(root.parentAddress == null && root.depth == 0) {
            "A persisted Session root Agent must have no parent and depth zero."
        }
        nodes.forEach { node ->
            node.parentAddress?.let { parentAddress ->
                val parent = requireNotNull(nodesByAddress[parentAddress]) {
                    "Every topology parent must be present in the same snapshot."
                }
                require(node.depth == parent.depth + 1) {
                    "A topology child depth must be exactly one greater than its parent."
                }
            }
        }
    }
}

/**
 * UI-facing lifetime of one persisted Session handle.
 *
 * The factory publishes a handle only after opening, so lifecycle does not
 * mirror the stable root Agent.
 */
public sealed interface PersistedSessionLifecycleState {
    public data object Open : PersistedSessionLifecycleState

    public data class Failed(
        public val detail: String,
    ) : PersistedSessionLifecycleState {
        init {
            require(detail.isNotBlank()) {
                "A persisted Session lifecycle failure detail must not be blank."
            }
        }
    }

    public data object Closing : PersistedSessionLifecycleState
    public data object Closed : PersistedSessionLifecycleState
}

public enum class PersistedSessionNotificationLevel {
    Information,
    Warning,
    Error,
}

/** Latest operation result owned only by one persisted Session. */
public data class PersistedSessionNotification(
    public val id: Long,
    public val level: PersistedSessionNotificationLevel,
    public val message: String,
    public val detail: String? = null,
) {
    init {
        require(id > 0) { "A persisted Session notification id must be positive." }
        require(message.isNotBlank()) {
            "A persisted Session notification message must not be blank."
        }
    }
}
