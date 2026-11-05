@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

public actual class SqliteAgentStorage actual constructor(
    internal val connection: SqliteConnection,
    agentId: Int,
    public actual override val uri: String,
    private val mutex: Mutex,
    private val requireAccess: () -> Unit,
) : MutableKodexAgentStorage {
    internal val agentId: Long = agentId.also { require(it >= 0) { "Invalid agent id: $it" } }.toLong()
    public actual override val index: SqliteIndexVersioned<CleanIndexEntry> =
        SqliteIndexVersioned(this, "index", CleanIndexEntry.serializer(), OpenAiJsonCodec)
    public actual override val work: SqliteIndexVersioned<StableWorkEvent> =
        SqliteIndexVersioned(this, "work", StableWorkEvent.serializer(), OpenAiJsonCodec)
    public actual override val settings: SqliteIndexVersioned<KodexAgentSettings> =
        SqliteIndexVersioned(this, "settings", KodexAgentSettings.serializer(), OpenAiJsonCodec)
    public actual override val timestamp: SqliteIndexVersioned<Instant> =
        SqliteIndexVersioned(this, "timestamp", serializer<Instant>(), OpenAiJsonCodec)
    public actual override val tokenCount: SqliteIndexVersioned<TokenCountSnapshot> =
        SqliteIndexVersioned(this, "token_count", serializer<TokenCountSnapshot>(), OpenAiJsonCodec)
    public actual override val unstable: SqliteIndexVersioned<List<UnstableCleanEvent>> =
        SqliteIndexVersioned(this, "unstable", ListSerializer(UnstableCleanEvent.serializer()), OpenAiJsonCodec)

    internal suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock { withContext(Dispatchers.IO) { requireAccess(); block() } }
    }

    internal suspend fun <T> write(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    requireAccess()
                    connection.transaction {
                        block()
                    }
                }
            }
        }
    }
}

public actual class SqliteIndexVersioned<T> internal constructor(
    private val storage: SqliteAgentStorage,
    private val timeline: String,
    private val serializer: KSerializer<T>,
    private val json: Json,
) : MutableIndexVersioned<T> {
    private fun rows(suffix: String = "", parameters: List<Any?> = emptyList()): List<List<Any?>> =
        storage.connection.query(
            "SELECT state_index, payload_json FROM agent_commits WHERE agent_id = ? AND timeline = ? $suffix",
            listOf(storage.agentId, timeline) + parameters,
        )
    private fun tail(): Int = rows("ORDER BY state_index DESC LIMIT 1").firstOrNull()?.let { (it[0] as Long).toInt() } ?: -1
    private fun decode(row: List<Any?>): T = json.decodeFromString(serializer, row[1] as String)
    public actual override suspend fun latestIndex(): Int = storage.read { tail() }
    public actual override suspend fun get(index: Int): T = storage.read {
        require(index >= 0)
        decode(rows("AND state_index <= ? ORDER BY state_index DESC LIMIT 1", listOf(index.toLong())).firstOrNull()
            ?: throw IllegalArgumentException("No value at index $index"))
    }
    public actual override suspend fun getExact(index: Int): T? = storage.read {
        require(index >= 0)
        rows("AND state_index = ?", listOf(index.toLong())).firstOrNull()?.let(::decode)
    }
    public actual override suspend fun floorToIndex(index: Int): Int? = storage.read {
        rows("AND state_index <= ? ORDER BY state_index DESC LIMIT 1", listOf(index.toLong())).firstOrNull()?.let { (it[0] as Long).toInt() }
    }
    public actual override suspend fun ceilToIndex(index: Int): Int? = storage.read {
        rows("AND state_index >= ? ORDER BY state_index LIMIT 1", listOf(index.toLong())).firstOrNull()?.let { (it[0] as Long).toInt() }
    }
    public actual override suspend fun indexesIn(range: IntRange): List<Int> = storage.read {
        if (range.isEmpty()) emptyList() else {
            require(range.first >= 0)
            rows("AND state_index BETWEEN ? AND ? ORDER BY state_index", listOf(range.first.toLong(), range.last.toLong()))
                .map { (it[0] as Long).toInt() }
        }
    }
    public actual override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> = storage.read {
        if (range.isEmpty()) emptyList() else {
            require(range.first >= 0)
            rows("AND state_index BETWEEN ? AND ? ORDER BY state_index", listOf(range.first.toLong(), range.last.toLong()))
                .map { (it[0] as Long).toInt() to decode(it) }
        }
    }
    public actual override suspend fun set(index: Int, value: T) {
        storage.write {
            require(index >= 0 && index > tail()) { "Timeline append requires an index beyond its tail" }
            val payload = json.encodeToString(serializer, value)
            storage.connection.execute(
                "INSERT INTO agent_commits VALUES (?, ?, ?, ?)",
                listOf(storage.agentId, timeline, index.toLong(), payload),
            )
        }
    }
    public actual override suspend fun revert(untilExclusive: Int) {
        storage.write {
            require(untilExclusive >= 0)
            storage.connection.execute(
                "DELETE FROM agent_commits WHERE agent_id = ? AND timeline = ? AND state_index >= ?",
                listOf(storage.agentId, timeline, untilExclusive.toLong()),
            )
        }
    }
}
