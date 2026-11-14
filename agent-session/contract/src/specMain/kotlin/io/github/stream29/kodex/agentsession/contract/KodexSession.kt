package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Default idle residency TTL granted or renewed by [KodexSessionRepository.open]. */
public val DefaultKodexSessionTtl: Duration = 60.seconds

/** Default interval for automatic TTL renewal while [AgentRuntime.runningTurn] is non-null. */
public val DefaultKodexSessionRenewalInterval: Duration = 20.seconds

/** [AgentStopEvent] paired with its owning root Session [entryIndex]. */
public data class IndexedAgentStopEvent(
    public val entryIndex: Int,
    public val event: AgentStopEvent,
)

/**
 * One exclusively owned root Agent in a Codex session.
 *
 * This interface combines one Agent's parallel storage timelines with the
 * runtime created when this session is opened.
 *
 * A newly created Agent is deliberately uninitialized. Its runtime initially
 * observes an empty state; the caller must initialize [runtime] before
 * requesting a model response. Directly initializing [storage] would bypass
 * the runtime's observable index and state publication.
 */
public interface KodexAgentSession : CoroutineScope {
    public val storage: MutableKodexAgentStorage

    /**
     * The AgentRuntime owned by this open session.
     *
     * AgentRuntime already implements the complete AgentState contract, so the
     * session does not expose a second state reference.
     */
    public val runtime: AgentRuntime
}

/** Read-only persisted metadata snapshot for one root Session entry. */
public interface KodexSessionEntry {
    /** Root Session entry index within the owning repository. */
    public val entryIndex: Int

    /** The latest thread name, or `null` while the entry is uninitialized. */
    public val threadName: String?

    /** Exact timestamp zero for this entry, or `null` when timestamp zero is absent. */
    public val createdAt: Instant?

    /** Latest recorded entry activity, or `null` when no timestamp has been persisted. */
    public val lastActivityAt: Instant?
}

/** Root Session metadata and commands exposed only by a root repository. */
public interface KodexRootSessionEntry : KodexSessionEntry {
    /** Whether this root Session was archived when the snapshot was listed. */
    public val archived: Boolean

    /** Whether this repository had a live owner for the entry when this snapshot was read. */
    public val isActive: Boolean

    /** Root runtime runningTurn presence sampled from that owner, not a live subscription. */
    public val running: Boolean

    /** Creates the idempotent archive marker for this root Session. */
    public suspend fun archive()

    /** Removes the idempotent archive marker for this root Session. */
    public suspend fun unarchive()
}

/**
 * One collection of root Session entries owning in-memory [KodexAgentSession] residency and TTL.
 *
 * Repeatedly calling [open] on an active entry returns the same [KodexAgentSession] instance
 * while renewing its residency deadline (`maxOf(deadline, now + sessionTtl)`). While an open
 * Session's [AgentRuntime.runningTurn] is non-null (including cancellation cleanup), the
 * repository automatically renews its residency deadline; when idle past the deadline, the
 * repository closes that in-memory [KodexAgentSession] (draining accepted session coroutines
 * before closing its session storage) without deleting persisted data.
 */
public interface KodexSessionRepository : CoroutineScope {
    /**
     * Ordered snapshot of this repository's root Session entry indices.
     *
     * The initial value reflects the entries that existed when this repository was opened.
     * Implementations publish a replacement snapshot after each successful [create] or [delete].
     */
    public val entries: StateFlow<List<Int>>

    /**
     * Snapshot of currently open (active in-memory) root Sessions keyed by [entryIndex].
     *
     * Reading or collecting [openSessions] neither opens unloaded entries nor renews residency TTL.
     */
    public val openSessions: StateFlow<Map<Int, KodexAgentSession>>

    /**
     * Aggregated [AgentRuntime.stopEvents] from all open Sessions in this repository.
     *
     * Subscriptions to each Session's [AgentRuntime.stopEvents] are attached before [open]
     * returns so immediate turns never miss an event.
     */
    public val stopEvents: Flow<IndexedAgentStopEvent>

    /** Returns the current [entries] snapshot in stable repository order. */
    public suspend fun list(): List<Int>

    /**
     * Reads lightweight direct-entry metadata without opening Agent runtimes.
     *
     * The returned list follows [list] order. An uninitialized entry has no [KodexSessionEntry.threadName].
     */
    public suspend fun listEntries(): List<KodexSessionEntry>

    /**
     * Creates one uninitialized direct Agent, then returns its entry index.
     *
     * After [open], initialize the returned session through its runtime.
     */
    public suspend fun create(): Int

    /**
     * Materializes a full copy of the source entry as one unopened direct entry.
     *
     * History range semantics are implemented by reverting the returned target
     * after it has been opened. Backends may use their native full-copy
     * primitive; this API does not expose a slow generic range copier.
     *
     * Implementations must remove the reserved entry if materialization fails.
     */
    public suspend fun createFork(sourceEntryIndex: Int): Int

    /**
     * Opens (or reuses) one root Session entry and grants/renews its residency TTL.
     */
    public suspend fun open(entryIndex: Int): KodexAgentSession

    /**
     * Closes the in-memory [KodexAgentSession] for [entryIndex] if currently open, draining
     * accepted session coroutines before closing its session storage without deleting persisted data.
     */
    public suspend fun closeSession(entryIndex: Int)

    /** Closes all currently open in-memory [KodexAgentSession]s without deleting persisted data. */
    public suspend fun closeAllSessions()

    /** Removes one root Session entry and its complete storage directory. */
    public suspend fun delete(entryIndex: Int)
}

/**
 * Returns the currently open [KodexAgentSession] for [entryIndex], or `null` when unloaded,
 * without opening it or renewing its residency TTL.
 */
public fun KodexSessionRepository.getOpen(entryIndex: Int): KodexAgentSession? =
    openSessions.value[entryIndex]

/**
 * Root Session repository capabilities.
 */
public interface KodexRootSessionRepository : KodexSessionRepository {
    /** Lists every root Session as an actionable root entry. */
    override suspend fun listEntries(): List<KodexRootSessionEntry>

    /** Reads one exact root Session as an actionable entry without scanning other metadata. */
    public suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry

    /**
     * Lists root Session catalog metadata.
     *
     * The complete [entries] and [list] inventory remains unaffected by
     * [includeArchived]. When it is `false`, archived entries are filtered
     * before their settings, timestamp, or timeline metadata is read.
     */
    public suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry>
}
