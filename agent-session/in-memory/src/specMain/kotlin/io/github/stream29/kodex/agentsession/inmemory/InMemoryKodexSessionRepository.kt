@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Process-local root session repository for tests and transient hosts. */
public expect class InMemoryKodexSessionRepository : KodexRootSessionRepository {
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

/** Creates an in-memory session repository owned by this scope. */
public expect fun CoroutineScope.InMemoryKodexSessionRepository(
    dependencies: KodexAgentDependencies,
): InMemoryKodexSessionRepository
