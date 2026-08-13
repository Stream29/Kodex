package io.github.stream29.kodex.app.history.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * Direction in which one finite history window can be extended.
 *
 * History ordering is newest-first. [Older] therefore extends the end of the
 * list, while [Newer] extends its beginning.
 */
public enum class AgentHistoryDirection {
    Older,
    Newer,
}

/**
 * A generation-scoped boundary for a finite history-window load.
 *
 * [storageIndexExclusive] is a real sparse storage index, not a list position.
 * An [AgentHistoryDirection.Older] request reads indexes below it; a
 * [AgentHistoryDirection.Newer] request reads indexes above it.
 */
public data class AgentHistoryCursor(
    public val generation: Long,
    public val storageIndexExclusive: Int,
    public val direction: AgentHistoryDirection,
) {
    init {
        require(generation >= 0) { "A history generation must not be negative." }
        require(storageIndexExclusive >= 0) {
            "A history cursor must use a non-negative exclusive storage boundary."
        }
    }
}

/** Stable identity for one semantic entry within a history generation. */
public data class AgentHistoryEntryKey(
    public val primaryStorageIndex: Int,
    public val providerId: String? = null,
) {
    init {
        require(primaryStorageIndex >= 0) {
            "A history entry must use a non-negative primary storage index."
        }
        require(providerId == null || providerId.isNotBlank()) {
            "A history provider id must be null or non-blank."
        }
    }
}

/**
 * One projected committed-history entry.
 *
 * [event] is a clean semantic value. Contract consumers never receive the
 * storage object, its raw cache, or a repository handle.
 */
public data class AgentHistoryEntry(
    public val key: AgentHistoryEntryKey,
    public val event: StableCleanEvent,
)

/** Initialization state for the current finite history window. */
public sealed interface AgentHistoryWindowStatus {
    /** The initial finite tail is still being projected. */
    public data object Initializing : AgentHistoryWindowStatus

    /** A complete finite snapshot is available. */
    public data object Ready : AgentHistoryWindowStatus

    /** Initial projection failed without replacing a previous ready window. */
    public data class Failed(
        public val message: String,
    ) : AgentHistoryWindowStatus {
        init {
            require(message.isNotBlank()) { "A history failure message must not be blank." }
        }
    }
}

/** Load state for one edge of a finite history window. */
public sealed interface AgentHistoryEdgeState {
    /** The edge has not been resolved by the initial projection yet. */
    public data object Unresolved : AgentHistoryEdgeState

    /** The storage timeline has no entries beyond this edge. */
    public data object Exhausted : AgentHistoryEdgeState

    /** More entries can be requested with [cursor]. */
    public data class Ready(
        public val cursor: AgentHistoryCursor,
    ) : AgentHistoryEdgeState

    /** The request identified by [cursor] is currently in flight. */
    public data class Loading(
        public val cursor: AgentHistoryCursor,
    ) : AgentHistoryEdgeState

    /** The request identified by [cursor] failed and can be retried. */
    public data class Failed(
        public val cursor: AgentHistoryCursor,
        public val message: String,
    ) : AgentHistoryEdgeState {
        init {
            require(message.isNotBlank()) { "A history edge failure message must not be blank." }
        }
    }
}

/**
 * Finite, newest-first semantic window over one Agent's committed history.
 *
 * [generation] changes after destructive replacement. Ordinary extension and
 * append operations retain it and reuse unchanged [entries] where possible.
 */
public data class AgentHistoryWindowSnapshot(
    public val generation: Long = 0,
    public val entries: List<AgentHistoryEntry> = emptyList(),
    public val olderEdge: AgentHistoryEdgeState = AgentHistoryEdgeState.Unresolved,
    public val newerEdge: AgentHistoryEdgeState = AgentHistoryEdgeState.Unresolved,
    public val status: AgentHistoryWindowStatus = AgentHistoryWindowStatus.Initializing,
) {
    init {
        require(generation >= 0) { "A history generation must not be negative." }
        require(entries.map(AgentHistoryEntry::key).distinct().size == entries.size) {
            "History entry keys must be unique within one window."
        }
        require(
            entries.zipWithNext().all { (newer, older) ->
                newer.key.primaryStorageIndex >= older.key.primaryStorageIndex
            },
        ) {
            "History entries must be ordered newest-first by primary storage index."
        }
        listOf(olderEdge, newerEdge).forEach { edge ->
            val cursor = when (edge) {
                is AgentHistoryEdgeState.Ready -> edge.cursor
                is AgentHistoryEdgeState.Loading -> edge.cursor
                is AgentHistoryEdgeState.Failed -> edge.cursor
                AgentHistoryEdgeState.Exhausted,
                AgentHistoryEdgeState.Unresolved,
                    -> null
            }
            require(cursor == null || cursor.generation == generation) {
                "A history edge cursor must belong to its window generation."
            }
        }
        require(edgeDirection(olderEdge) != AgentHistoryDirection.Newer) {
            "The older history edge cannot carry a newer cursor."
        }
        require(edgeDirection(newerEdge) != AgentHistoryDirection.Older) {
            "The newer history edge cannot carry an older cursor."
        }
    }
}

/** One bounded request to extend a finite history window. */
public data class AgentHistoryLoadRequest(
    public val cursor: AgentHistoryCursor,
    public val itemBudget: Int,
) {
    init {
        require(itemBudget > 0) { "A history item budget must be positive." }
    }
}

/**
 * Thin, independently observable history projection for one Agent ViewModel.
 *
 * Implementations reuse Agent storage's index/value caches. They must not
 * expose storage or build a second raw history repository.
 */
public interface AgentHistoryViewModel : AutoCloseable {
    /** Current finite committed-history window. */
    public val window: StateFlow<AgentHistoryWindowSnapshot>

    /**
     * Requests one bounded extension.
     *
     * Stale generations, duplicate in-flight cursors, and cursors that no
     * longer match the published edge are ignored.
     */
    public fun request(request: AgentHistoryLoadRequest)

    override fun close(): Unit
}

private fun edgeDirection(edge: AgentHistoryEdgeState): AgentHistoryDirection? = when (edge) {
    is AgentHistoryEdgeState.Ready -> edge.cursor.direction
    is AgentHistoryEdgeState.Loading -> edge.cursor.direction
    is AgentHistoryEdgeState.Failed -> edge.cursor.direction
    AgentHistoryEdgeState.Exhausted,
    AgentHistoryEdgeState.Unresolved,
        -> null
}
