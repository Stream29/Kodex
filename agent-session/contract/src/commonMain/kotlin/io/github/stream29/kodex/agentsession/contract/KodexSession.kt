package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * One exclusively owned Agent node in a recursive Codex session tree.
 *
 * This interface combines one Agent's seven storage timelines with the
 * repository of its direct child Agents. [runtime] is created when this
 * session is opened and remains owned by the session's coroutine lifecycle.
 *
 * A newly created Agent is deliberately uninitialized. Its runtime initially
 * observes an empty state; the caller must initialize [runtime] before
 * requesting a model response. Directly initializing [storage] would bypass
 * the runtime's observable index and state publication.
 */
public interface KodexAgentSession : CoroutineScope {
    public val storage: MutableKodexAgentStorage

    public val subagents: KodexSessionRepository

    /**
     * The AgentRuntime owned by this open session.
     *
     * AgentRuntime already implements the complete AgentState contract, so the
     * session does not expose a second state reference.
     */
    public val runtime: AgentRuntime
}

/** Read-only persisted metadata snapshot for one direct Agent entry. */
public interface KodexSessionEntry {
    /** Direct-entry index within the owning repository. */
    public val entryIndex: Int

    /** The latest thread name, or `null` while the entry is uninitialized. */
    public val threadName: String?

    /** Latest recorded entry activity, or `null` when no timestamp has been persisted. */
    public val lastActivityAt: Instant?
}

/** Root Session metadata and commands exposed only by a root repository. */
public interface KodexRootSessionEntry : KodexSessionEntry {
    /** Whether this root Session was archived when the snapshot was listed. */
    public val archived: Boolean

    /** Creates the idempotent archive marker for this root Session. */
    public suspend fun archive()

    /** Removes the idempotent archive marker for this root Session. */
    public suspend fun unarchive()
}

/**
 * One collection of direct Agent entries in a recursive Codex session tree.
 *
 * Repeatedly opening the same active entry returns the same [KodexAgentSession]
 * instance. Root Agents and subagents share this contract; their runtime
 * composition is an implementation detail of the repository that creates
 * them.
 */
public interface KodexSessionRepository : CoroutineScope {
    /**
     * Ordered snapshot of this repository's direct Agent entry indices.
     *
     * The initial value reflects the entries that existed when this repository was opened.
     * Implementations publish a replacement snapshot after each successful [create] or [delete].
     */
    public val entries: StateFlow<List<Int>>

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

    /** Opens one direct Agent entry. */
    public suspend fun open(entryIndex: Int): KodexAgentSession

    /** Removes one direct Agent entry and its complete descendant tree. */
    public suspend fun delete(entryIndex: Int)
}

/**
 * Root Session repository capabilities that are intentionally not inherited by
 * recursive Subagent repositories.
 */
public interface KodexRootSessionRepository : KodexSessionRepository {
    /** Lists every root Session as an actionable root entry. */
    override suspend fun listEntries(): List<KodexRootSessionEntry>

    /**
     * Lists root Session catalog metadata.
     *
     * The complete [entries] and [list] inventory remains unaffected by
     * [includeArchived]. When it is `false`, archived entries are filtered
     * before their settings, timestamp, or timeline metadata is read.
     */
    public suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry>
}
