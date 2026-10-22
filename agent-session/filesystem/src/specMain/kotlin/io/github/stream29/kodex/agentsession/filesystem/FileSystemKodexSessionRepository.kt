@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.filesystem

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Filesystem-backed root session repository. */
public expect class FileSystemKodexSessionRepository : KodexRootSessionRepository {
    override val coroutineContext: CoroutineContext
    override val entries: StateFlow<List<Int>>
    override suspend fun list(): List<Int>
    override suspend fun listEntries(): List<KodexRootSessionEntry>
    override suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry>
    override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry
    override suspend fun readCreatedAt(entryIndex: Int): Instant?
    override suspend fun readUpdatedAt(entryIndex: Int): Instant?
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun delete(entryIndex: Int)
}

/** Creates a filesystem session repository, initializing its root layout when needed. */
public expect suspend fun CoroutineScope.FileSystemKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    valueCacheSize: Int = 1_024,
): FileSystemKodexSessionRepository
