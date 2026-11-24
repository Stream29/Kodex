@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

public data class StoredSessionGroup(
    public val cwd: String?,
    public val groupNonce: Long,
    public val latestUpdatedAt: Instant?,
    public val hasRunning: Boolean,
)

public data class StoredSessionGroupPage(
    public val groupNonce: Long,
    public val entries: List<KodexSessionEntry>,
    public val hiddenCount: Int,
)

public expect class SqliteKodexSessionRepository : KodexSessionRepository, CoroutineCloseable {
    /** Opens an existing Agent only if it still has the expected armed deadline. */
    public suspend fun openForAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline): KodexAgentSession?
    /** Exact persisted Agent lookup; does not start or renew an owner. */
    override suspend fun getAgentRecord(agentId: Int): StoredAgent?
    override suspend fun openAgent(agentId: Int): KodexAgentSession?
    /** Allocates an empty child in the persisted parent's Session without opening it. */
    public suspend fun createChild(parentAgentId: Int, expectedOwner: KodexAgentSession? = null): StoredAgent
    /** Deletes only an uninitialized child with no commits or children, after failed creation. */
    public suspend fun discardUninitializedChild(agentId: Int): Boolean
    /** Marks an existing child completed; returns its direct parent only if the marker changed. */
    public suspend fun markChildCompleted(agentId: Int): Int?
    override val coroutineContext: CoroutineContext
    override val entries: StateFlow<List<Int>>
    override val openSessions: StateFlow<Map<Int, KodexAgentSession>>
    override val pendingAwaitDeadlines: StateFlow<Map<Int, PendingAwaitDeadline>>
    override val stopEvents: Flow<IndexedAgentStopEvent>
    override suspend fun list(): List<Int>
    override suspend fun listEntries(includeArchived: Boolean): List<KodexSessionEntry>
    public val sessionGroupsNonce: Flow<Long>
    public fun sessionGroupNonce(cwd: String?): Flow<Long>
    public suspend fun listSessionGroups(): List<StoredSessionGroup>
    public suspend fun listSessionsInGroup(cwd: String?, includeArchived: Boolean, limit: Int?): StoredSessionGroupPage
    override suspend fun getEntry(entryIndex: Int): KodexSessionEntry
    override suspend fun getRootAgentId(entryIndex: Int): Int
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun createAgentFork(sourceAgentId: Int, expectedOwner: KodexAgentSession?, untilExclusive: Int?): AgentForkCopy?
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun closeSession(entryIndex: Int)
    override suspend fun closeAllSessions()
    override suspend fun delete(entryIndex: Int)
    override suspend fun close()
    public suspend fun closeAndJoin()
}

/**
 * Opens the existing, signed current `kodex.db` under [root]. Production callers hold an
 * already-prepared Home lease. [allowFresh] is only for isolated storage without Home state.
 * SQLite initialization and failed/cancelled acquisition cleanup use [dispatcher]; after
 * a successful return the caller owns the connection and must close it on the same lane.
 * @throws kotlinx.io.IOException if the required database is missing or incompatible.
 */
public expect suspend fun openVerifiedKodexDatabase(
    root: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    allowFresh: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Pair<Path, SqliteConnection>

public expect suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    sessionTtl: Duration = DefaultKodexSessionTtl,
    timeSource: TimeSource = TimeSource.Monotonic,
    onTurnActiveRenewal: suspend () -> Unit = {},
    turnRenewalInterval: () -> Duration = { DefaultKodexSessionRenewalInterval },
    reportMirrorFailure: (Throwable) -> Unit = {},
): SqliteKodexSessionRepository

/**
 * Opens a [SqliteKodexSessionRepository] borrowing caller-owned [connection], [mutex] and [dispatcher]
 * on `kodex.db` (e.g. shared with a peer backend service); closing this repository leaves
 * the connection and dispatcher open for the outer owner to close after all sharing services stop.
 */
public expect suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    connection: SqliteConnection,
    mutex: Mutex,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    sessionTtl: Duration = DefaultKodexSessionTtl,
    timeSource: TimeSource = TimeSource.Monotonic,
    onTurnActiveRenewal: suspend () -> Unit = {},
    turnRenewalInterval: () -> Duration = { DefaultKodexSessionRenewalInterval },
    reportMirrorFailure: (Throwable) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onDeleteInTransaction: (sessionId: Int) -> Unit = {},
): SqliteKodexSessionRepository
