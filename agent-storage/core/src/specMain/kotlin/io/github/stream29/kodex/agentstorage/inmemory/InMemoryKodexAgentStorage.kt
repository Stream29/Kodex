@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlin.time.Instant

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published commits in memory. Construction
 * publishes the required snapshot-zero settings, timestamp, and token count,
 * so it can always back a legal empty agent state without a synthetic
 * compaction point.
 */
public expect class InMemoryKodexAgentStorage(initialSettings: KodexAgentSettings) : MutableKodexAgentStorage {
    override val uri: String

    override suspend fun latestIndex(): Int
    override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun commit(commits: List<AgentCommit>): Int
    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int
    override suspend fun revert(untilExclusive: Int)

    public companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public fun empty(): InMemoryKodexAgentStorage
    }
}
