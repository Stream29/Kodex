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
import kotlin.time.Duration

/**
 * Stable identity and minimal presentation state for one materialized history item.
 *
 * An item deliberately retains no decoded event. Top-level item objects are lazy-list keys, while
 * a [WorkGroup] privately retains its exact child items so their expansion state survives folding.
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

    /** Completed user input is rendered as a tool but always breaks an automatic work group. */
    public class RequestUserInput(
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

    /**
     * A virtual row describing one history turn's elapsed time.
     *
     * This item is projected from a turn marker or the non-running timeline end. Its placement is
     * owned by the history projection and it deliberately does not pretend to be a stored event.
     */
    public class TurnTimeMarker(
        public val markerIndex: Int,
        public val endIndex: Int,
        public val duration: Duration,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(markerIndex)
            requireValidHistoryIndex(endIndex)
            require(endIndex > markerIndex) {
                "A turn time marker must follow at least one stable item in its turn."
            }
            require(duration >= Duration.ZERO && duration.isFinite()) {
                "A turn time marker duration must be finite and non-negative."
            }
        }
    }

    /**
     * One folded, newest-first run of reasoning, ordinary tool, and patch items.
     *
     * [indexRange] is the sparse storage span, not a claim that every integer in it is a child.
     */
    public class WorkGroup(
        children: List<HistoryItemViewModel>,
        initiallyExpanded: Boolean = false,
    ) : HistoryItemViewModel {
        private val children: List<HistoryItemViewModel> = children.toList()

        init {
            require(this.children.size > 1) {
                "A history work group must contain at least two items."
            }
            require(this.children.all { child -> child.isWorkGroupChild() }) {
                "A history work group may contain only reasoning, ordinary tool, and patch items."
            }
            require(this.children.zipWithNext().all { (newer, older) ->
                newer.individualIndex > older.individualIndex
            }) {
                "History work group children must be strictly newest-first."
            }
        }

        public val indexRange: IntRange =
            this.children.last().individualIndex..this.children.first().individualIndex

        public val itemCount: Int = this.children.size

        public var expanded: Boolean by mutableStateOf(initiallyExpanded)
            private set

        public fun childAt(position: Int): HistoryItemViewModel = children[position]

        public fun toggleExpanded() {
            expanded = !expanded
        }
    }
}

/**
 * One immutable newest-first snapshot of the materialized history-item window.
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

/** Current structural loading state of the materialized history sequence. */
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
 * Materialized history items, pending tools, and the streaming item are independent projections.
 * Storage remains private and supplies decoded stable events on demand through [read].
 */
public interface AgentHistoryViewModel : AutoCloseable {
    /** Atomically published materialized history items. */
    public val historyItems: StateFlow<HistoryItemWindow>

    public val loadState: StateFlow<AgentHistoryLoadState>

    public val pendingTools: StateFlow<List<UnstableCleanEvent>>

    public val streamingItem: StateFlow<HistoryStreamingItem?>

    /** Elapsed duration of the currently active turn, rendered by the composer. */
    public val activeTurnDuration: StateFlow<Duration?>

    /** Scroll state used by the single Mosaic History View. */
    public val listState: LazyListState

    public val scrollInteractionSource: MutableScrollInteractionSource

    /** Observable Compose state indicating whether content changes follow the latest row. */
    public val followsLatest: Boolean

    /** Reads a stable history event represented by [item] through the storage value LRU. */
    public suspend fun read(item: HistoryItemViewModel): StableCleanEvent

    /**
     * Returns the elapsed wall-clock time from the preceding stable event to [item].
     *
     * For a [HistoryItemViewModel.WorkGroup], the interval starts at the stable event before
     * its oldest child and ends at its newest child. `null` means that no preceding event or exact
     * timestamp pair is available.
     */
    public suspend fun elapsedSincePrevious(item: HistoryItemViewModel): Duration?

    /** Validates a generation-scoped stable storage target. */
    public fun contains(generation: Long, storageIndex: Int): Boolean

    /** Reconciles a renderer-local content-size change with current follow-latest intent. */
    public fun notifyContentChanged()

    /** Restores follow-latest intent and requests the logical newest position. */
    public fun requestScrollToLatest()

    override fun close()
}

private fun requireValidHistoryIndex(index: Int) {
    require(index >= 0) { "A history index must not be negative." }
}

private fun HistoryItemViewModel.isWorkGroupChild(): Boolean = when (this) {
    is HistoryItemViewModel.Reasoning,
    is HistoryItemViewModel.Tool,
    is HistoryItemViewModel.Patch,
        -> true

    is HistoryItemViewModel.Message,
    is HistoryItemViewModel.RequestUserInput,
    is HistoryItemViewModel.PlanUpdate,
    is HistoryItemViewModel.ContextCompaction,
    is HistoryItemViewModel.TurnTimeMarker,
    is HistoryItemViewModel.WorkGroup,
        -> false
}

private val HistoryItemViewModel.individualIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.Message -> index
        is HistoryItemViewModel.Reasoning -> index
        is HistoryItemViewModel.Tool -> index
        is HistoryItemViewModel.RequestUserInput -> index
        is HistoryItemViewModel.Patch -> index
        is HistoryItemViewModel.PlanUpdate -> index
        is HistoryItemViewModel.ContextCompaction -> index
        is HistoryItemViewModel.TurnTimeMarker -> error("A turn time marker has no stable item index.")
        is HistoryItemViewModel.WorkGroup -> error("A work group cannot be nested.")
    }
