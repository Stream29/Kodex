package io.github.stream29.kodex.app.history.contract

import androidx.compose.runtime.Stable
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One immutable newest-first snapshot of the materialized history-item window.
 *
 * The window is the only first-level collection exposed to the View. Its implementation owns the
 * persistent index and requests older projection when the renderer reads near its older edge.
 * Individual item ViewModels remain alive across window publication and viewport changes.
 */
@Stable
public interface HistoryItemWindow {
    /** Destructive replacement generation for context-action validation. */
    public val generation: Long

    public val size: Int

    /** Returns an already materialized child without registering viewport demand. */
    public fun peek(index: Int): HistoryItemViewModel

    /** Returns a child and registers demand near the loaded older edge. */
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
 * Stable items, pending tools, and the streaming item are independent projections. The View only
 * renders their state and sends scroll/explicit expansion commands; it never reads storage.
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

    /** Validates a generation-scoped stable storage target for a context menu. */
    public fun contains(generation: Long, storageIndex: Int): Boolean

    /** Reconciles a renderer-local content-size change with current follow-latest intent. */
    public fun notifyContentChanged()

    /** Restores follow-latest intent and requests the logical newest position. */
    public fun requestScrollToLatest()

    override fun close()
}
