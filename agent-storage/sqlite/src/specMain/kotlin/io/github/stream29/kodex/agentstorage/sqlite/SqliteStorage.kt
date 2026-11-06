@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.sync.Mutex

public expect class SqliteAgentStorage(
    connection: SqliteConnection,
    agentId: Int,
    uri: String = "db:agent/$agentId",
    mutex: Mutex = Mutex(),
    requireAccess: () -> Unit = {},
) : MutableKodexAgentStorage {
    override val uri: String

    override suspend fun latestIndex(): Int
    override suspend fun indexesIn(range: IntRange, types: Set<AgentCommitType>): List<Int>
    override suspend fun getCommitsExact(index: Int, types: Set<AgentCommitType>): List<AgentCommit>
    override suspend fun getCommitsIn(range: IntRange, types: Set<AgentCommitType>): List<IndexedAgentCommit>
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun commit(commits: List<AgentCommit>): Int
    override suspend fun revert(untilExclusive: Int)
}
