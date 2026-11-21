package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
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
    public val agentId: Int = entryIndex,
)

/**
 * One exclusively owned root Agent in a Codex session.
 *
 * This interface combines one Agent's observable mutable storage with the
 * runtime created when this session is opened.
 */
public interface KodexAgentSession : CoroutineScope {
    public val sessionId: Int
    public val storage: MutableObservableKodexAgentStorage

    /** The AgentRuntime owned by this open session. */
    public val runtime: AgentRuntime
}

/** Persisted metadata and commands for one root Session entry. */
public interface KodexSessionEntry {
    /** Root Session entry index within the owning repository. */
    public val entryIndex: Int

    /** The persisted Agent ID of this Session's root. It is not the Session ID. */
    public val rootAgentId: Int

    /** The latest root Agent name, or `null` while the entry is uninitialized. */
    public val name: String?

    /** Exact timestamp zero for this entry, or `null` when timestamp zero is absent. */
    public val createdAt: Instant?

    /** Latest recorded entry activity, or `null` when no timestamp has been persisted. */
    public val lastActivityAt: Instant?

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

/** Persisted identity and direct parent relation, independent of runtime residency. */
public data class StoredAgent(
    public val agentId: Int,
    public val sessionId: Int,
    public val parentAgentId: Int?,
    public val completed: Boolean,
)

/** An unopened, independent Session and its source settings at the captured fork boundary. */
public data class AgentForkCopy(
    public val sessionId: Int,
    public val boundarySettings: KodexAgentSettings,
)

/** The source's live owner changed before storage capture. */
public class AgentForkSourceChanged : IllegalStateException("The fork source's active owner changed.")

/**
 * One collection of root Session entries owning in-memory [KodexAgentSession] residency and TTL.
 */
public interface KodexSessionRepository : CoroutineScope {
    /** Ordered snapshot of this repository's root Session entry indices. */
    public val entries: StateFlow<List<Int>>

    /** Snapshot of current runtime owners keyed by AgentId (root or child). */
    public val openSessions: StateFlow<Map<Int, KodexAgentSession>>

    /** The earliest armed timeout for each Agent, including nonresident Sessions. */
    public val pendingAwaitDeadlines: StateFlow<Map<Int, PendingAwaitDeadline>>

    /** Aggregated [AgentRuntime.stopEvents] from all open Sessions in this repository. */
    public val stopEvents: Flow<IndexedAgentStopEvent>

    /** Returns the current [entries] snapshot in stable repository order. */
    public suspend fun list(): List<Int>

    /** Reads one exact root Session as an actionable entry without scanning other metadata. */
    public suspend fun getEntry(entryIndex: Int): KodexSessionEntry

    /** Looks up the exact root without opening or renewing the Agent. */
    public suspend fun getRootAgentId(entryIndex: Int): Int = getEntry(entryIndex).rootAgentId

    /** Persisted identity lookup; never opens or renews an owner. */
    public suspend fun getAgentRecord(agentId: Int): StoredAgent?

    /** Opens or renews an exact Agent; returns null if it does not exist. */
    public suspend fun openAgent(agentId: Int): KodexAgentSession?

    /**
     * Lists root Session catalog metadata.
     *
     * The complete [entries] and [list] inventory remains unaffected by [includeArchived].
     * When `false`, archived entries are filtered out.
     */
    public suspend fun listEntries(includeArchived: Boolean = true): List<KodexSessionEntry>

    /** Creates one uninitialized direct Agent, then returns its entry index. */
    public suspend fun create(): Int

    /** Internal full-entry copy used by storage operations; not the Agent fork command. */
    public suspend fun createFork(sourceEntryIndex: Int): Int

    /**
     * Copies the effective history of the exact source Agent to a new root. A null boundary
     * captures the latest window; otherwise the boundary is exclusive. Returns null for a
     * missing Agent. [expectedOwner] is the owner already locked by the caller, or null for a
     * nonresident source. A changed owner rejects the capture rather than rebinding it.
     */
    public suspend fun createAgentFork(
        sourceAgentId: Int,
        expectedOwner: KodexAgentSession?,
        untilExclusive: Int?,
    ): AgentForkCopy?

    /** Opens (or reuses) one root Session entry and grants/renews its residency TTL. */
    public suspend fun open(entryIndex: Int): KodexAgentSession

    /** Closes the in-memory [KodexAgentSession] for [entryIndex] if currently open. */
    public suspend fun closeSession(entryIndex: Int)

    /** Closes all currently open in-memory [KodexAgentSession]s without deleting persisted data. */
    public suspend fun closeAllSessions()

    /** Removes one root Session entry and its complete storage. */
    public suspend fun delete(entryIndex: Int)
}

/** Explicit deletion did not start because a child of the Session is running. */
public class RunningSubagentsPreventDeletion : IllegalStateException("The Session has running subagents.")
