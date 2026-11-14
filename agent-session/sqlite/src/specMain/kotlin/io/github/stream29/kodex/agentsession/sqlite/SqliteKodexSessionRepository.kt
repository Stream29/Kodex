@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

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

public expect class SqliteKodexSessionRepository : KodexRootSessionRepository, CoroutineCloseable {
    override val coroutineContext: CoroutineContext
    override val entries: StateFlow<List<Int>>
    override val openSessions: StateFlow<Map<Int, KodexAgentSession>>
    override val stopEvents: Flow<IndexedAgentStopEvent>
    override suspend fun list(): List<Int>
    override suspend fun listEntries(): List<KodexRootSessionEntry>
    override suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry>
    override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun closeSession(entryIndex: Int)
    override suspend fun closeAllSessions()
    override suspend fun delete(entryIndex: Int)
    override suspend fun close()
    public suspend fun closeAndJoin()
}

public expect suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    valueCacheSize: Int = 1_024,
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
    valueCacheSize: Int = 1_024,
    sessionTtl: Duration = DefaultKodexSessionTtl,
    timeSource: TimeSource = TimeSource.Monotonic,
    onTurnActiveRenewal: suspend () -> Unit = {},
    turnRenewalInterval: () -> Duration = { DefaultKodexSessionRenewalInterval },
    reportMirrorFailure: (Throwable) -> Unit = {},
): SqliteKodexSessionRepository
