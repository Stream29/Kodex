@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant

/**
 * [dispatcher] is borrowed; share it and [mutex] with the other users of [connection].
 * Construct this handle with exclusive access to [connection] (hold [mutex] when shared).
 *
 * [onHistoryChanged] runs after commit and after publishing observable state under the database
 * mutex, for appends and reverts that actually changed history. It receives changed types or
 * `null` for a revert. The callback must not suspend, reacquire the mutex, or throw.
 * [onSessionGroupsChanged] has the same after-commit boundary and is called only for roots
 * with a catalog change; the map reports affected cwd keys and whether each still exists.
 */
public expect class SqliteAgentStorage(
    connection: SqliteConnection,
    agentId: Int,
    mutex: Mutex = Mutex(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    requireAccess: () -> Unit = {},
    onHistoryChanged: ((Set<AgentCommitType>?) -> Unit)? = null,
    onSessionGroupsChanged: ((Map<String?, Boolean>) -> Unit)? = null,
) : MutableObservableKodexAgentStorage {
    override val agentId: Int
    override val cacheNonce: StateFlow<Long>
    override val latestIndexFlow: StateFlow<Int>

    override suspend fun latestIndex(): Int
    override suspend fun cacheNonce(): Long
    override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun commit(commits: List<AgentCommit>): Int
    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int
    override suspend fun revert(untilExclusive: Int)

    /** Refreshes the published tail and nonce from SQLite without emitting a timeline callback. */
    public suspend fun refreshObservableState()
}
