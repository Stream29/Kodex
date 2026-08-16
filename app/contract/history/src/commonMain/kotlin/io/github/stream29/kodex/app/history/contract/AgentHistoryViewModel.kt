package io.github.stream29.kodex.app.history.contract

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable identity and minimal presentation state for one committed top-level history row.
 *
 * A child deliberately retains no decoded event. The child object itself is the lazy-list key.
 */
@Stable
public sealed interface HistoryItemViewModel {
    public class Message(
        public val index: Int,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }
    }

    public class Reasoning(
        public val index: Int,
        initiallyExpanded: Boolean = false,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }

        public var expanded: Boolean by mutableStateOf(initiallyExpanded)
            private set

        public fun toggleExpanded() {
            expanded = !expanded
        }
    }

    public class Tool(
        public val index: Int,
        initiallyExpanded: Boolean = false,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }

        public var expanded: Boolean by mutableStateOf(initiallyExpanded)
            private set

        public fun toggleExpanded() {
            expanded = !expanded
        }
    }

    public class Patch(
        public val index: Int,
        initiallyExpanded: Boolean = false,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }

        public var expanded: Boolean by mutableStateOf(initiallyExpanded)
            private set

        public fun toggleExpanded() {
            expanded = !expanded
        }
    }

    public class PlanUpdate(
        public val index: Int,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }
    }

    public class ContextCompaction(
        public val index: Int,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(index)
        }
    }
}

/**
 * One immutable newest-first snapshot of the materialized committed-item window.
 *
 * [size], [peek], and [get] always address the same sequence. A previously published window
 * remains indexable after a newer window replaces it so a lazy renderer can finish an in-flight
 * measure safely.
 */
@Stable
public interface HistoryItemWindow {
    /** Destructive replacement generation for context-action validation. */
    public val generation: Long

    public val size: Int

    /** Returns an already materialized child without registering viewport demand. */
    public fun peek(index: Int): HistoryItemViewModel

    /** Returns a child and registers demand when [index] approaches the loaded older edge. */
    public operator fun get(index: Int): HistoryItemViewModel
}

/** Current structural loading state of the committed child sequence. */
public sealed interface AgentHistoryLoadState {
    public data object Initializing : AgentHistoryLoadState

    public data class Ready(
        public val hasOlder: Boolean,
    ) : AgentHistoryLoadState

    public data object LoadingOlder : AgentHistoryLoadState

    public data class Failed(
        public val message: String,
    ) : AgentHistoryLoadState {
        init {
            require(message.isNotBlank()) { "A history failure message must not be blank." }
        }
    }
}

/** Semantic kind of the currently streaming Responses output item. */
public enum class HistoryStreamingKind {
    Message,
    AgentMessage,
    Reasoning,
    ToolCall,
    Unknown,
}

/** At most one active high-frequency row rendered after pending tools. */
public sealed interface HistoryStreamingItem {
    public data object Started : HistoryStreamingItem

    public data class Output(
        public val kind: HistoryStreamingKind,
        public val events: SharedFlow<ResponsesStreamEvent>,
    ) : HistoryStreamingItem

    public data object Compacting : HistoryStreamingItem
}

/**
 * Complete History View state and interaction owner for one materialized Agent.
 *
 * Committed children, pending tools, and the streaming item are independent projections. Storage
 * remains private and supplies decoded committed events on demand through [read].
 */
public interface AgentHistoryViewModel : AutoCloseable {
    /** Atomically published materialized committed children. */
    public val committedItems: StateFlow<HistoryItemWindow>

    public val loadState: StateFlow<AgentHistoryLoadState>

    public val pendingTools: StateFlow<List<UnstableCleanEvent>>

    public val streamingItem: StateFlow<HistoryStreamingItem?>

    /** Scroll state used by the single Mosaic History View. */
    public val listState: LazyListState

    public val scrollInteractionSource: MutableScrollInteractionSource

    /** Observable Compose state indicating whether content changes follow the latest row. */
    public val followsLatest: Boolean

    /** Reads the exact committed event represented by [item] through the storage value LRU. */
    public suspend fun read(item: HistoryItemViewModel): StableCleanEvent

    /** Validates a generation-scoped committed storage target. */
    public fun contains(generation: Long, storageIndex: Int): Boolean

    /** Reconciles a renderer-local content-size change with current follow-latest intent. */
    public fun notifyContentChanged()

    /** Restores follow-latest intent and requests the logical newest position. */
    public fun requestScrollToLatest()

    override fun close()
}

private fun requireValidHistoryIndex(index: Int) {
    require(index >= 0) { "A committed history index must not be negative." }
}
