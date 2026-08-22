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
 * Stable identity and minimal presentation state for one committed history item.
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
     * A virtual row after one history turn.
     *
     * [positionIndex] is the next turn marker, so this row has a stable order in the committed
     * sequence without pretending to be a stored event. [duration] is display-ready metadata.
     */
    public class TurnFooter(
        public val markerIndex: Int,
        public val endIndex: Int,
        public val positionIndex: Int,
        public val duration: Duration,
    ) : HistoryItemViewModel {
        init {
            requireValidHistoryIndex(markerIndex)
            requireValidHistoryIndex(endIndex)
            requireValidHistoryIndex(positionIndex)
            require(endIndex > markerIndex) {
                "A turn footer must follow at least one stable item in its turn."
            }
            require(positionIndex > endIndex) {
                "A turn footer must be ordered after its last stable item."
            }
            require(duration >= Duration.ZERO && duration.isFinite()) {
                "A turn footer duration must be finite and non-negative."
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

/** A completed turn duration rendered after the turn's last stable item. */
@Stable
public data class HistoryTurnFooterState(
    public val markerIndex: Int,
    public val endIndex: Int,
    public val duration: Duration,
) {
    init {
        requireValidHistoryIndex(markerIndex)
        requireValidHistoryIndex(endIndex)
        require(endIndex > markerIndex) {
            "A history turn footer must follow at least one stable item in its turn."
        }
        require(duration >= Duration.ZERO && duration.isFinite()) {
            "A history turn footer duration must be finite and non-negative."
        }
    }
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

    /** Historical turn duration rendered after the latest stable item. */
    public val historyTurnFooter: StateFlow<HistoryTurnFooterState?>

    /** Elapsed duration of the currently active turn, rendered by the composer. */
    public val activeTurnDuration: StateFlow<Duration?>

    /** Scroll state used by the single Mosaic History View. */
    public val listState: LazyListState

    public val scrollInteractionSource: MutableScrollInteractionSource

    /** Observable Compose state indicating whether content changes follow the latest row. */
    public val followsLatest: Boolean

    /** Reads a stored history event represented by [item] through the storage value LRU. */
    public suspend fun read(item: HistoryItemViewModel): StableCleanEvent

    /**
     * Returns the elapsed wall-clock time from the preceding committed event to [item].
     *
     * For a [HistoryItemViewModel.WorkGroup], the interval starts at the committed event before
     * its oldest child and ends at its newest child. `null` means that no preceding event or exact
     * timestamp pair is available.
     */
    public suspend fun elapsedSincePrevious(item: HistoryItemViewModel): Duration?

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

private fun HistoryItemViewModel.isWorkGroupChild(): Boolean = when (this) {
    is HistoryItemViewModel.Reasoning,
    is HistoryItemViewModel.Tool,
    is HistoryItemViewModel.Patch,
        -> true

    is HistoryItemViewModel.Message,
    is HistoryItemViewModel.RequestUserInput,
    is HistoryItemViewModel.PlanUpdate,
    is HistoryItemViewModel.ContextCompaction,
    is HistoryItemViewModel.TurnFooter,
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
        is HistoryItemViewModel.TurnFooter -> error("A turn footer has no stable item index.")
        is HistoryItemViewModel.WorkGroup -> error("A work group cannot be nested.")
    }
