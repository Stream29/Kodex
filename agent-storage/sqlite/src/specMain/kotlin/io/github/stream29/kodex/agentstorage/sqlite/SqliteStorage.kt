@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [dispatcher] is borrowed; share it and [mutex] with the other users of [connection].
 *
 * [onHistoryChanged] runs after commit under the database mutex. It receives changed types
 * or `null` for a revert. The callback must not suspend, reacquire the mutex, or throw.
 * [onSessionGroupsChanged] has the same after-commit boundary and is called only for roots
 * with a catalog change; the map reports affected cwd keys and whether each still exists.
 */
public expect class SqliteAgentStorage(
    connection: SqliteConnection,
    agentId: Int,
    uri: String = "db:agent/$agentId",
    mutex: Mutex = Mutex(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    requireAccess: () -> Unit = {},
    onHistoryChanged: ((Set<AgentCommitType>?) -> Unit)? = null,
    onSessionGroupsChanged: ((Map<String?, Boolean>) -> Unit)? = null,
) : MutableKodexAgentStorage {
    override val agentId: Int
    override val uri: String

    override suspend fun latestIndex(): Int
    override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun commit(commits: List<AgentCommit>): Int
    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int
    public suspend fun commitWithMirrorPayloads(
        commits: List<AgentCommit>,
        timestamp: Instant = Clock.System.now(),
    ): Pair<Int, List<Pair<String, String>>>
    override suspend fun revert(untilExclusive: Int)
}
