package io.github.stream29.kodex.app.history.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One immutable newest-first snapshot of the materialized history-item window.
 *
 * The window is the only first-level collection exposed to the View. The renderer requests
 * adjacent storage chunks when either edge enters the viewport. The implementation may evict the
 * opposite edge, including its item ViewModels, after protecting reported visible children.
 * Old snapshots remain structurally readable after replacement, but their released children
 * cannot start new reads. Paging commands on an obsolete snapshot are ignored.
 */
public interface HistoryItemWindow {
    /** Destructive replacement generation for context-action validation. */
    public val generation: Long

    /** Number of top-level rows; an expanded Work Group still occupies one row. */
    public val size: Int

    /** An adjacent older structural chunk may be requested. */
    public val hasOlder: Boolean

    /** Newer stored history is outside this bounded window, not necessarily in the viewport. */
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

    /**
     * Requests one adjacent older structural chunk, without reading row payloads.
     * Duplicate in-flight, stale-window and post-close demand is ignored. Structural failures
     * publish the owner's Failed state, not an empty page. Demand requires Ready and an older
     * edge; a full command buffer may reject it without changing state. A later edge demand may
     * try again; no automatic retry or retained navigation intent is created. No synchronous throws.
     */
    public fun requestOlder()

    /**
     * Requests one adjacent newer structural chunk, preserving reported visible children.
     * Duplicate in-flight, stale-window and post-close demand is ignored. Structural failures
     * publish the owner's Failed state, not an empty page. Demand requires Ready and a newer
     * edge; a full command buffer may reject it without changing state. A later edge demand may
     * try again; no automatic retry or retained navigation intent is created. No synchronous throws.
     */
    public fun requestNewer()
}

/** Current structural loading state of the materialized history sequence. */
public sealed interface AgentHistoryLoadState {
    /** Render initial/replacement loading independently of pending and streaming content. */
    public data object Initializing : AgentHistoryLoadState

    /** Render rows and enable visible-edge demands, including an empty-history marker. */
    public data object Ready : AgentHistoryLoadState

    /** Keep existing rows and render the older-edge loading marker. */
    public data object LoadingOlder : AgentHistoryLoadState

    /** Keep existing rows and render the newer-edge loading marker. */
    public data object LoadingNewer : AgentHistoryLoadState

    /**
     * Render a failure marker rather than treating unreadable history as empty.
     * @throws IllegalArgumentException if [message] is blank.
     */
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
    /** Render the turn-started indicator, before the stored window. */
    public data object Started : HistoryStreamingItem

    /** Render this borrowed high-frequency stream in one transient row. */
    public data class Output(
        public val kind: HistoryStreamingKind,
        public val events: SharedFlow<ResponsesStreamEvent>,
    ) : HistoryStreamingItem

    /** Render the transient compaction indicator. */
    public data object Compacting : HistoryStreamingItem
}

/** Portable destination; it carries no widget offset, transient-prefix count or storage resolver. */
public sealed interface HistoryScrollTarget {
    /** Scroll to the logical newest position, including pending/streaming content. */
    public data object Latest : HistoryScrollTarget

    /** Scroll to this exact top-level child in the materialized window. */
    public data class Item(public val item: HistoryItemViewModel) : HistoryScrollTarget
}

/**
 * One immutable navigation presentation published only after its destination has been loaded.
 *
 * Equality is instance identity: repeated requests for the same destination publish distinct
 * instances. The renderer executes against [generation] and the exact child (if any), then
 * acknowledges this instance. Unmounting does not consume it. Superseding navigation, generation
 * invalidation of its generation, destination eviction and owner close withdraw it. A delayed
 * invalidation of an older generation cannot withdraw newer navigation. When an authoritative
 * cache nonce is supplied, delayed external-write completion for an already reconciled nonce
 * is not a second invalidation: it cannot release the current destination or consume this effect.
 * No retry or journal is implied.
 */
public class HistoryScrollEffect(
    public val generation: Long,
    public val target: HistoryScrollTarget,
)

/**
 * History data and interaction owner for one materialized Agent.
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
 * With an authoritative cache nonce, destructive replacement is reconciled before navigation
 * for that nonce. Completion of the same external write only reconciles the actual latest
 * cursor, not its older captured end; completion from an obsolete nonce cannot regress the
 * window. Without a nonce, destructive external-write completion still invalidates the local
 * generation, including a same-cursor rewrite, and withdraws its previous children/effect.
 *
 * The owner calls [close] when this Agent binding is released. Unmounting or changing tabs must
 * not close it. The renderer owns scroll state and focus/viewport presentation, and context
 * menus capture the displayed generation plus storage index and validate [contains] before acting.
 */
public interface AgentHistoryViewModel : AutoCloseable {
    /** Atomically published materialized history items. */
    public val historyItems: StateFlow<HistoryItemWindow>

    /** Structural loading/failure presentation; payload failures remain on their exact children. */
    public val loadState: StateFlow<AgentHistoryLoadState>

    /** Pending tools in storage order; renderer reverses them independently of stored rows. */
    public val pendingTools: StateFlow<List<UnstableCleanEvent>>

    /** Transient newest row, or null when no streaming presentation is active. */
    public val streamingItem: StateFlow<HistoryStreamingItem?>

    /** Elapsed duration of the currently active turn, rendered by the composer. */
    public val activeTurnDuration: StateFlow<Duration?>

    /**
     * Portable intent, initially true. Genuine scrolling away sets false; genuine return to
     * newest may set true only without newer stored history. Explicit Latest navigation restores
     * true; exact-index navigation uses whether its loaded destination is the newest stored row.
     * Renderer height changes maintain follow locally, without a VM command.
     */
    public val followsLatest: StateFlow<Boolean>

    /** Single pending navigation slot, retained until exact acknowledgment or withdrawal. */
    public val pendingScrollEffect: StateFlow<HistoryScrollEffect?>

    /**
     * Reports actual visible top-level children from the exact captured [window].
     * Work Group nested content reports its owning top-level group. The latest valid report
     * protects original visible chunks during opposite-edge eviction. Stale/released windows or
     * foreign children are ignored; no payload read is started. The list is copied on acceptance.
     * An empty valid report clears retention, including when the renderer unmounts.
     * This operation is harmless after close and does not throw.
     */
    public fun reportViewport(window: HistoryItemWindow, visibleItems: List<HistoryItemViewModel>)

    /**
     * Updates follow intent from renderer-classified genuine pointer/keyboard scrolling.
     * The renderer sends true only when genuinely at newest; true is also rejected while the
     * captured [window] has newer storage. Focus relocation and programmatic scrolling must not
     * call this operation. Stale/released windows and calls after close are ignored; no throws.
     */
    public fun setFollowsLatest(window: HistoryItemWindow, followsLatest: Boolean)

    /**
     * Consumes only the exact pending [effect] after renderer execution. A late/duplicate ack
     * cannot clear a newer instance. Calls after withdrawal/close are harmless; no throws.
     */
    public fun acknowledgeScrollEffect(effect: HistoryScrollEffect)

    /** Validates a generation-scoped stable storage target; false after close; no throws. */
    public fun contains(generation: Long, storageIndex: Int): Boolean

    /**
     * Restores follow intent and loads the latest bounded window before publishing a Latest
     * effect. Requests coalesce to the newest pending navigation even while reads are blocked
     * and the bounded command buffer is full. Cache-nonce invalidation precedes navigation for
     * that new generation, preserving a request already made for it; old-generation commands
     * cannot revive withdrawn destinations. Delayed completion of the write that produced the
     * same nonce must not withdraw the loaded window/effect. No-op after close; load failures
     * publish Failed, not an effect; cancellation of the owner stops loading. No synchronous throws.
     */
    public fun requestScrollToLatest()

    /**
     * Enqueues navigation to a committed index-timeline storage position, materializing the
     * needed bounded window when absent. This is a scroll command only, never revert/fork/write.
     * The renderer retains the stable row anchor when paging displaces the opposite edge.
     * Repeated targets are distinct intents; the newest accepted navigation supersedes older
     * pending/in-flight navigation, including under bounded-buffer pressure.
     * Missing/unreadable targets publish Failed, not a successful
     * scroll. No-op after close; no synchronous throws.
     */
    public fun requestScrollToStorageIndex(storageIndex: Int)

    /**
     * Releases cached payloads and Work Group children, closes the command loop, and cancels the
     * dedicated History scope. Borrowed storage/Agent state remains open. No backend rollback or
     * retry is implied; stale reads cannot republish a replacement window.
     * Pending navigation and viewport retention are withdrawn. Repeated close is harmless.
     * This operation performs local cleanup only and does not throw.
     */
    override fun close()
}
