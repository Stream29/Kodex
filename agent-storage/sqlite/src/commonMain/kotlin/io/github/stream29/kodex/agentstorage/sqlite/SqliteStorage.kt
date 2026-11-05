@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessagePhase
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

private val SqliteIndexCommitTypes: List<String> = listOf(
    "user_message",
    "assistant_message",
    "assistant_message_commentary",
    "assistant_message_final",
    "developer_message",
    "agent_message",
    "request_user_input_tool_event",
    "suggest_subagent_task_tool_event",
    "plan_update",
    "compaction_point",
)

private val SqliteWorkCommitTypes: List<String> = listOf(
    "reasoning",
    "context_compaction",
    "command_execution_tool_event",
    "patch_tool_event",
    "mcp_tool_event",
    "web_search_tool_event",
    "image_generation_tool_event",
    "image_view_tool_event",
    "tool_search_event",
    "invalid_tool_call",
    "server_tool_search",
    "hosted_web_search",
    "hosted_image_generation",
    "json_tool_event",
    "text_tool_event",
    "custom_tool_event",
)

private val SqliteTokenCountCommitTypes: List<String> = listOf(
    "token_count_legacy",
    "token_count_initialization",
    "token_count_compaction",
    "token_count_response",
)

private fun CleanIndexEntry.toSqliteCommitType(): String = when (this) {
    is StableUserMessage -> "user_message"
    is StableAssistantMessage -> when (phase) {
        MessagePhase.Commentary -> "assistant_message_commentary"
        MessagePhase.FinalAnswer -> "assistant_message_final"
        null -> "assistant_message"
    }
    is StableDeveloperMessage -> "developer_message"
    is StableAgentMessage -> "agent_message"
    is StableRequestUserInputToolEvent -> "request_user_input_tool_event"
    is StableSuggestSubagentTaskToolEvent -> "suggest_subagent_task_tool_event"
    is StablePlanUpdate -> "plan_update"
    CleanCompactionPoint -> "compaction_point"
}

private fun StableWorkEvent.toSqliteCommitType(): String = when (this) {
    is StableReasoning -> "reasoning"
    is StableContextCompaction -> "context_compaction"
    is StableCommandExecutionToolEvent -> "command_execution_tool_event"
    is StablePatchToolEvent -> "patch_tool_event"
    is StableMcpToolEvent -> "mcp_tool_event"
    is StableWebSearchToolEvent -> "web_search_tool_event"
    is StableImageGenerationToolEvent -> "image_generation_tool_event"
    is StableImageViewToolEvent -> "image_view_tool_event"
    is StableToolSearchEvent -> "tool_search_event"
    is StableInvalidToolCall -> "invalid_tool_call"
    is StableServerToolSearch -> "server_tool_search"
    is StableWebSearchCall -> "hosted_web_search"
    is StableImageGenerationCall -> "hosted_image_generation"
    is StableJsonToolEvent -> "json_tool_event"
    is StableTextToolEvent -> "text_tool_event"
    is StableCustomToolEvent -> "custom_tool_event"
}

private fun TokenCountSnapshot.toSqliteCommitType(): String = when (kind) {
    TokenCountKind.Legacy -> "token_count_legacy"
    TokenCountKind.Initialization -> "token_count_initialization"
    TokenCountKind.Compaction -> "token_count_compaction"
    TokenCountKind.Response -> "token_count_response"
}

public actual class SqliteAgentStorage actual constructor(
    internal val connection: SqliteConnection,
    agentId: Int,
    public actual override val uri: String,
    private val mutex: Mutex,
    private val requireAccess: () -> Unit,
) : MutableKodexAgentStorage {
    internal val agentId: Long = agentId.also { require(it >= 0) { "Invalid agent id: $it" } }.toLong()
    public actual override val index: SqliteIndexVersioned<CleanIndexEntry> =
        SqliteIndexVersioned(this, SqliteIndexCommitTypes, CleanIndexEntry::toSqliteCommitType, CleanIndexEntry.serializer(), OpenAiJsonCodec)
    public actual override val work: SqliteIndexVersioned<StableWorkEvent> =
        SqliteIndexVersioned(this, SqliteWorkCommitTypes, StableWorkEvent::toSqliteCommitType, StableWorkEvent.serializer(), OpenAiJsonCodec)
    public actual override val settings: SqliteIndexVersioned<KodexAgentSettings> =
        SqliteIndexVersioned(this, listOf("settings"), { "settings" }, KodexAgentSettings.serializer(), OpenAiJsonCodec)
    public actual override val timestamp: SqliteIndexVersioned<Instant> =
        SqliteIndexVersioned(this, listOf("timestamp"), { "timestamp" }, serializer<Instant>(), OpenAiJsonCodec)
    public actual override val tokenCount: SqliteIndexVersioned<TokenCountSnapshot> =
        SqliteIndexVersioned(this, SqliteTokenCountCommitTypes, TokenCountSnapshot::toSqliteCommitType, serializer<TokenCountSnapshot>(), OpenAiJsonCodec)
    public actual override val unstable: SqliteIndexVersioned<List<UnstableCleanEvent>> =
        SqliteIndexVersioned(this, listOf("unstable"), { "unstable" }, ListSerializer(UnstableCleanEvent.serializer()), OpenAiJsonCodec)

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
    private val types: List<String>,
    private val typeOf: (T) -> String,
    private val serializer: KSerializer<T>,
    private val json: Json,
) : MutableIndexVersioned<T> {
    private val typeClause: String =
        if (types.size == 1) "type = ?"
        else "type IN (${types.joinToString(",") { "?" }})"

    private fun rows(suffix: String = "", parameters: List<Any?> = emptyList()): List<List<Any?>> =
        storage.connection.query(
            """SELECT "index", payload_json FROM agent_commits WHERE agent_id = ? AND $typeClause $suffix""",
            listOf(storage.agentId) + types + parameters,
        )
    private fun tail(): Int = rows("""ORDER BY "index" DESC LIMIT 1""").firstOrNull()?.let { (it[0] as Long).toInt() } ?: -1
    private fun decode(row: List<Any?>): T = json.decodeFromString(serializer, row[1] as String)
    public actual override suspend fun latestIndex(): Int = storage.read { tail() }
    public actual override suspend fun get(index: Int): T = storage.read {
        require(index >= 0)
        decode(rows("""AND "index" <= ? ORDER BY "index" DESC LIMIT 1""", listOf(index.toLong())).firstOrNull()
            ?: throw IllegalArgumentException("No value at index $index"))
    }
    public actual override suspend fun getExact(index: Int): T? = storage.read {
        require(index >= 0)
        rows("""AND "index" = ?""", listOf(index.toLong())).firstOrNull()?.let(::decode)
    }
    public actual override suspend fun floorToIndex(index: Int): Int? = storage.read {
        rows("""AND "index" <= ? ORDER BY "index" DESC LIMIT 1""", listOf(index.toLong())).firstOrNull()?.let { (it[0] as Long).toInt() }
    }
    public actual override suspend fun ceilToIndex(index: Int): Int? = storage.read {
        rows("""AND "index" >= ? ORDER BY "index" LIMIT 1""", listOf(index.toLong())).firstOrNull()?.let { (it[0] as Long).toInt() }
    }
    public actual override suspend fun indexesIn(range: IntRange): List<Int> = storage.read {
        if (range.isEmpty()) emptyList() else {
            require(range.first >= 0)
            rows("""AND "index" BETWEEN ? AND ? ORDER BY "index"""", listOf(range.first.toLong(), range.last.toLong()))
                .map { (it[0] as Long).toInt() }
        }
    }
    public actual override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> = storage.read {
        if (range.isEmpty()) emptyList() else {
            require(range.first >= 0)
            rows("""AND "index" BETWEEN ? AND ? ORDER BY "index"""", listOf(range.first.toLong(), range.last.toLong()))
                .map { (it[0] as Long).toInt() to decode(it) }
        }
    }
    public actual override suspend fun set(index: Int, value: T) {
        storage.write {
            require(index >= 0 && index > tail()) { "Timeline append requires an index beyond its tail" }
            val type = typeOf(value)
            val payload = json.encodeToString(serializer, value)
            storage.connection.execute(
                "INSERT INTO agent_commits VALUES (?, ?, ?, ?)",
                listOf(storage.agentId, type, index.toLong(), payload),
            )
        }
    }
    public actual override suspend fun revert(untilExclusive: Int) {
        storage.write {
            require(untilExclusive >= 0)
            storage.connection.execute(
                """DELETE FROM agent_commits WHERE agent_id = ? AND $typeClause AND "index" >= ?""",
                listOf(storage.agentId) + types + listOf(untilExclusive.toLong()),
            )
        }
    }
}
