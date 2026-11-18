@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

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
    override suspend fun getEntry(entryIndex: Int): KodexSessionEntry
    override suspend fun getRootAgentId(entryIndex: Int): Int
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun closeSession(entryIndex: Int)
    override suspend fun closeAllSessions()
    override suspend fun delete(entryIndex: Int)
    override suspend fun close()
    public suspend fun closeAndJoin()
}

/**
 * Resolves [root], verifies that an existing Kodex Home has not lost an initialized `kodex.db`,
 * and opens a writable [SqliteConnection] on `kodex.db`.
 */
public expect suspend fun openVerifiedKodexDatabase(
    root: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
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
 * Opens a [SqliteKodexSessionRepository] borrowing a caller-owned [connection] and [mutex]
 * on `kodex.db` (e.g. shared with a peer backend service); closing this repository leaves
 * [connection] open for its outer owner to close after all sharing services have stopped.
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
    onDeleteInTransaction: (sessionId: Int) -> Unit = {},
): SqliteKodexSessionRepository
