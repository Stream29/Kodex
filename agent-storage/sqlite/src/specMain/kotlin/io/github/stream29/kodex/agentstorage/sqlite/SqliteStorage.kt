@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant

public expect class SqliteAgentStorage(
    connection: SqliteConnection,
    agentId: Int,
    uri: String = "db:agent/$agentId",
    mutex: Mutex = Mutex(),
    requireAccess: () -> Unit = {},
) : MutableKodexAgentStorage {
    override val uri: String
    override val index: SqliteIndexVersioned<CleanIndexEntry>
    override val work: SqliteIndexVersioned<StableWorkEvent>
    override val settings: SqliteIndexVersioned<KodexAgentSettings>
    override val timestamp: SqliteIndexVersioned<Instant>
    override val tokenCount: SqliteIndexVersioned<TokenCountSnapshot>
    override val unstable: SqliteIndexVersioned<List<UnstableCleanEvent>>
}

public expect class SqliteIndexVersioned<T> : MutableIndexVersioned<T> {
    override suspend fun latestIndex(): Int
    override suspend fun get(index: Int): T
    override suspend fun getExact(index: Int): T?
    override suspend fun floorToIndex(index: Int): Int?
    override suspend fun ceilToIndex(index: Int): Int?
    override suspend fun indexesIn(range: IntRange): List<Int>
    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>>
    override suspend fun set(index: Int, value: T)
    override suspend fun revert(untilExclusive: Int)
}
