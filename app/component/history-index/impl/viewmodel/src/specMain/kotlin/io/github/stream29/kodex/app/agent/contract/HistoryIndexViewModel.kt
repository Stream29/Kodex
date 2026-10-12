package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Oldest-first snapshot of one captured Agent's sparse index timeline. Indexes are actual stored
 * positions, not offsets or a contiguous range. Initial index-zero compaction is omitted.
 * [generation] equals the production index cache nonce (equality only, never ordering);
 * nonce-less local fixtures use a local generation advanced on destructive rescans.
 * Append retains generation. A renderer may follow the newest row until the user scrolls away,
 * and resume following on reaching the end; this viewport preference is not business state.
 */
public data class HistoryIndexWindow(
    public val generation: Long,
    public val indexes: List<Int>,
    /**
     * Local render/read invalidation only, not a backend nonce or history command credential.
     * Changes on replacement scans, including equal indexes with an unchanged production nonce.
     * Renderer re-acquires its rows and discards old interaction requests on this change.
     */
    public val revision: Long = 0,
)

/** Exact entry classification; renderer uses the corresponding message/tool title in hover. */
public enum class HistoryIndexEntryKind {
    CompactionPoint, UserMessage, AssistantMessage, AssistantCommentary, AssistantFinal,
    DeveloperMessage, AgentMessage, RequestUserInput, SuggestSubagents, PlanUpdate,
}

/**
 * One exact row: render graph plus summary, terminal-width ellipsized, without a timestamp.
 * Compaction summary is `Context compacted`; message summaries concatenate text plus `[image]`
 * or `[encrypted content]` placeholders. Request-user-input uses all question texts, not answers;
 * suggest-subagents uses only `suggest subagents`. Plan summary selects the last non-pending
 * step, otherwise the first step. Summaries trim/collapse whitespace and use `[empty]` if blank.
 */
public data class HistoryIndexEntry(
    public val index: Int,
    public val kind: HistoryIndexEntryKind,
    public val summary: String,
)

/**
 * Full renderer-neutral hover. Text is trimmed, blank content becomes `[empty]`, image/encrypted
 * content uses placeholders, and secret answers are hidden. Suggest-subagents remains a simple
 * label; plan detail includes explanation and every step. [requestUserInput] is the original
 * stable event for the shared pure visual projection: that renderer must also hide secret answers.
 */
public data class HistoryIndexEntryDetail(
    public val kind: HistoryIndexEntryKind,
    public val content: String,
    public val requestUserInput: StableRequestUserInputToolEvent? = null,
)

/**
 * A consumer-owned asynchronous read, never a second window authority. Cancellation is Closed,
 * not Failed. Failed exposes no raw exception/secret. Released/invalidated handles stay Closed
 * permanently, including when a dependency completes non-cooperatively after release.
 */
public sealed interface HistoryIndexReadState<out T> {
    /** Row shows `…`; detail has no popup yet; timestamp information is omitted. */
    public data object Loading : HistoryIndexReadState<Nothing>
    /** Row shows safe `[error]`; detail shows safe error text; timestamp information is omitted. */
    public data object Failed : HistoryIndexReadState<Nothing>
    /** Hide hover/menu. A disposed row must not be resurrected or given a new target. */
    public data object Closed : HistoryIndexReadState<Nothing>
    /** Render exact value; timestamp Ready(null) still omits the optional information field. */
    public data class Ready<T>(public val value: T) : HistoryIndexReadState<T>
}

/**
 * One independently acquired consumer lifetime. Two sidebars may read the same exact target
 * through independent handles; releasing either never releases the other's read or the child VM.
 */
public interface HistoryIndexReadHandle<out T> {
    /** Captured identity, not latest window/selection. */
    public val generation: Long
    public val index: Int
    public val state: StateFlow<HistoryIndexReadState<T>>
    /** Idempotently publish Closed and cancel only this wait; does not close borrowed sources. */
    public fun release(): Unit
}

/**
 * Borrowed lightweight ports bound to ONE captured Agent, not an Application/Agent service locator.
 * All source operations retain their existing errors and caller cancellation; the component
 * cancels its waiters, never the RPC client, timeline, Session, or source owner.
 * Implementations use the owner's serialized interaction context for synchronous commands.
 */
public interface HistoryIndexDependencies {
    /** Ascending sparse queries and exact reads; no store, mutations, or history commands. */
    public val timeline: IndexVersioned<CleanIndexEntry>
    /** Exact Message timestamp reads only; do not floor, substitute current time, or parse URI. */
    public val timestamp: IndexVersioned<Instant>
    /** Index timeline's own latest position; -1 means empty. */
    public val latestIndex: StateFlow<Int>
    /**
     * Production must supply the index timeline's observed nonce. Null is only for local fixtures:
     * local fallback generation is NOT an RPC/backend nonce. No nonce is generated by the browser.
     */
    public val cacheNonce: StateFlow<Long>?
    /**
     * True while the captured Agent is externally writing. On observed true->false, a tail not advancing
     * triggers replacement rescan even at the same index. Other Agent state is not required.
     */
    public val externalWrite: StateFlow<Boolean>
    /**
     * Synchronously request complete History viewport positioning on the captured Agent.
     * No selected-Agent lookup, revert, fork, new RPC, or run/resume. Called only after exact
     * generation/index admission; delivery does not guarantee the History has finished scrolling.
     * @throws CancellationException if delivery is cancelled.
     * @throws Exception if the host's scroll request fails.
     */
    public fun requestScrollToStorageIndex(index: Int): Unit
}

/** Typed construction port. The returned stable child owns only jobs under the supplied owner scope. */
public fun interface HistoryIndexViewModelFactory {
    /**
     * Begins sparse observation/scanning; does not block on initial I/O. Ordinary scan failures
     * propagate to the owner's coroutine exception handling, never masquerade as empty success.
     * Cache-nonce races await subscribed metadata, without replaying a stale query.
     * @throws Exception if construction cannot establish the child.
     */
    public fun create(dependencies: HistoryIndexDependencies, ownerScope: CoroutineScope): HistoryIndexViewModel
}

/**
 * Single sparse-window authority, held as a stable Agent child. Renderer unmount releases its
 * read handles, NOT this shared VM. Close child before releasing borrowed Agent timeline owners.
 * Synchronous lifecycle/commands run on the owner's interaction dispatcher.
 */
public interface HistoryIndexViewModel : AutoCloseable {
    /** Sparse oldest-first projection. Initial empty value is not proof that scanning succeeded. */
    public val window: StateFlow<HistoryIndexWindow>
    /** False after close or owner cancellation; retained window is no longer actionable. */
    public val isActive: Boolean
    /** False for closed, removed index, destructive scan in flight, or differing nonce/generation. */
    public fun contains(generation: Long, index: Int): Boolean

    /**
     * Exact row read with validation before and after I/O; no retries or index substitution.
     * @throws IllegalStateException if closed, removed/replaced, absent, or read/decode fails.
     * @throws CancellationException if the caller's wait is cancelled.
     */
    public suspend fun load(generation: Long, index: Int): HistoryIndexEntry
    /**
     * Exact detail read; same admission and cancellation as [load].
     * @throws IllegalStateException if closed, removed/replaced, absent, or read/decode fails.
     * @throws CancellationException if the caller's wait is cancelled.
     */
    public suspend fun loadDetail(generation: Long, index: Int): HistoryIndexEntryDetail
    /**
     * Exact timestamp only for Message entries; null for non-Message or missing exact timestamp.
     * Validation includes the wait for the timestamp itself, not only the entry classification.
     * @throws IllegalStateException if target/entry is invalid or entry read/decode fails.
     * @throws Exception if the borrowed timestamp read fails.
     * @throws CancellationException if the caller's wait is cancelled.
     */
    public suspend fun readMessageTimestamp(generation: Long, index: Int): Instant?

    /** Start one row read. Invalid/closed target returns an already Closed handle, without I/O. */
    public fun acquireRow(generation: Long, index: Int): HistoryIndexReadHandle<HistoryIndexEntry>
    /** Start one detail read; hover delay/grace belongs to renderer, before acquiring this handle. */
    public fun acquireDetail(generation: Long, index: Int): HistoryIndexReadHandle<HistoryIndexEntryDetail>
    /** Start one optional timestamp read owned by this exact menu opening. */
    public fun acquireTimestamp(generation: Long, index: Int): HistoryIndexReadHandle<Instant?>

    /**
     * Check out means scroll only. Returns false without delivery if closed/stale/removed; true
     * means the narrow captured-Agent port was called, not backend mutation or scroll completion.
     * Never re-resolves a latest selected Agent or substitutes a newer generation/index.
     * @throws CancellationException if synchronous port delivery is cancelled.
     * @throws Exception if synchronous port delivery fails.
     */
    public fun checkOut(generation: Long, index: Int): Boolean
    /** Idempotently invalidate handles/cancel child jobs only; never closes borrowed sources. */
    override fun close(): Unit
}
