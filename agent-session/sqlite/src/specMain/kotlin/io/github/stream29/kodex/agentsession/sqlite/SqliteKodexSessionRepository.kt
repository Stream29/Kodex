@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext

public expect class SqliteKodexSessionRepository : KodexRootSessionRepository, CoroutineCloseable {
    override val coroutineContext: CoroutineContext
    override val entries: StateFlow<List<Int>>
    override suspend fun list(): List<Int>
    override suspend fun listEntries(): List<KodexRootSessionEntry>
    override suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry>
    override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun delete(entryIndex: Int)
    override suspend fun close()
    public suspend fun closeAndJoin()
}

public expect suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    valueCacheSize: Int = 1_024,
    reportMirrorFailure: (Throwable) -> Unit = {},
): SqliteKodexSessionRepository
