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
 * The window is the only first-level collection exposed to the View. The renderer requests
 * adjacent storage chunks when either edge enters the viewport. The implementation may evict the
 * opposite edge, including its item ViewModels, after preserving the visible LazyColumn anchor.
 */
@Stable
public interface HistoryItemWindow {
    /** Destructive replacement generation for context-action validation. */
    public val generation: Long

    public val size: Int

    public val hasOlder: Boolean

    public val hasNewer: Boolean

    /**
     * Returns the exact materialized child without starting its payload read.
     * @throws IndexOutOfBoundsException if [index] is outside this snapshot's visible rows.
     */
    public fun peek(index: Int): HistoryItemViewModel

    /**
     * Returns that same child and starts its lazy payload read once. Repeated viewport access
     * does not create another state machine. Read failure is published by the child, not as empty
     * content. This index addresses the bounded window, not a storage timeline.
     * @throws IndexOutOfBoundsException if [index] is outside this snapshot's visible rows.
     */
    public operator fun get(index: Int): HistoryItemViewModel

    /** Requests the adjacent older structural chunk. Duplicate in-flight demand is ignored. */
    public fun requestOlder()

    /** Requests the adjacent newer structural chunk. Duplicate in-flight demand is ignored. */
    public fun requestNewer()
}

/** Current structural loading state of the materialized history sequence. */
public sealed interface AgentHistoryLoadState {
    public data object Initializing : AgentHistoryLoadState

    public data object Ready : AgentHistoryLoadState

    public data object LoadingOlder : AgentHistoryLoadState

    public data object LoadingNewer : AgentHistoryLoadState

    /** @throws IllegalArgumentException if [message] is blank. */
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
 * This is Agent history across index/work/unstable timelines, not one timeline or one Session.
 *
 * Initial loading reads the tail; edge demand loads one adjacent structural chunk and bounds the
 * window by evicting the opposite edge. Expensive payloads belong to cached item ViewModels.
 * Work Groups own their exact newest-first children while expanded and release them on collapse.
 * Cache invalidation replaces the generation and releases obsolete children; late reads cannot
 * publish into the new generation. Structural failures publish [AgentHistoryLoadState.Failed];
 * cancellation is not converted into a successful or empty load.
 *
 * The owner calls [close] when this Agent binding is released. Unmounting or changing tabs must
 * not close it. The renderer borrows scroll state and focus/viewport presentation, and context
 * menus capture the displayed generation plus storage index and validate [contains] before acting.
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

    /**
     * Enqueues navigation to a committed index-timeline storage position, materializing the
     * needed bounded window when absent. This is a scroll command only, never revert/fork/write.
     * The renderer retains the stable row anchor when paging displaces the opposite edge.
     */
    public fun requestScrollToStorageIndex(storageIndex: Int)

    /**
     * Releases cached payloads and Work Group children, closes the command loop, and cancels the
     * dedicated History scope. Borrowed storage/Agent state remains open. No backend rollback or
     * retry is implied; stale reads cannot republish a replacement window.
     */
    override fun close()
}
