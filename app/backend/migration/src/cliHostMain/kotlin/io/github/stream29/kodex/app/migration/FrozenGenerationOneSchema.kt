package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

// Released DDL, not derived from the live storage schema.
private val V4Ddl = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647)
) STRICT;

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))
) STRICT;

CREATE UNIQUE INDEX idx_agents_root_session ON agents(session_id) WHERE parent_agent_id IS NULL;

CREATE TABLE agent_commits (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (
        type IN (
            'user_message',
            'event_ask_user',
            'event_schedule',
            'event_terminal_exited',
            'event_subagent_created',
            'event_subagent_completed',
            'assistant_message',
            'assistant_message_commentary',
            'assistant_message_final',
            'developer_message',
            'agent_message',
            'await_event_tool_event',
            'plan_update',
            'reasoning',
            'context_compaction',
            'command_execution_tool_event',
            'patch_tool_event',
            'read_media_tool_event',
            'invalid_tool_call',
            'server_tool_search',
            'hosted_web_search',
            'hosted_image_generation',
            'json_tool_event',
            'text_tool_event',
            'custom_tool_event',
            'settings',
            'llm_request_meta_legacy',
            'llm_request_meta_initialization',
            'llm_request_meta_compaction',
            'llm_request_meta_response',
            'unstable',
            'steer',
            'ask_user'
        )
    ),
    "index" INTEGER NOT NULL CHECK ("index" BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, type, "index")
) STRICT;

CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type);
CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, "index" DESC
) WHERE type = 'settings';

CREATE TABLE session_catalog_view (
    root_agent_id INTEGER PRIMARY KEY REFERENCES agents(agent_id) ON DELETE CASCADE,
    name TEXT,
    cwd TEXT,
    created_at TEXT,
    updated_at TEXT
) STRICT;

CREATE TABLE schedules (
    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
    agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
    trigger_at_epoch_seconds INTEGER NOT NULL,
    trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
    cron TEXT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT;

CREATE INDEX idx_schedules_trigger_at
    ON schedules(trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);
CREATE INDEX idx_schedules_agent_id
    ON schedules(agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);

CREATE TABLE session_groups (
    cwd_key TEXT PRIMARY KEY,
    cwd TEXT UNIQUE,
    group_nonce INTEGER NOT NULL DEFAULT 1
) STRICT;
CREATE INDEX idx_session_catalog_view_cwd_updated
    ON session_catalog_view (cwd, updated_at DESC, root_agent_id DESC);
""".split(';').map(String::trim).filter(String::isNotEmpty)

private val ForkRecoveryDdl = """
CREATE TABLE fork_context_recovery (
    agent_id INTEGER NOT NULL,
    checkpoint_index INTEGER NOT NULL,
    commit_type TEXT NOT NULL DEFAULT 'context_compaction' CHECK (commit_type = 'context_compaction'),
    phase TEXT NOT NULL CHECK (phase IN ('Empty', 'UserMessage', 'AssistantMessage', 'ToolCompleted')),
    starts_new_turn INTEGER NOT NULL CHECK (starts_new_turn IN (0, 1)),
    PRIMARY KEY (agent_id, checkpoint_index),
    FOREIGN KEY (agent_id, commit_type, checkpoint_index)
        REFERENCES agent_commits(agent_id, type, "index") ON DELETE CASCADE
) STRICT, WITHOUT ROWID
""".trim()

private val V3Ddl = V4Ddl + ForkRecoveryDdl
private val V2Ddl = V3Ddl.map { if (it.startsWith("CREATE TABLE agent_commits (")) "$it, WITHOUT ROWID" else it }
internal const val FrozenCacheNonceColumn = "cache_nonce INTEGER NOT NULL DEFAULT 1 CHECK (cache_nonce >= 1)"
private val V5Ddl = V4Ddl.map {
    if (it.startsWith("CREATE TABLE agents (")) it.removeSuffix(") STRICT") + ", $FrozenCacheNonceColumn) STRICT" else it
}

internal const val GenerationOneApplicationId = 0x4B445830L
internal const val RecentUpgradeSchemaVersion = 2

internal fun frozenHomeDdl(version: Int): List<String> = when (version) {
    2 -> V2Ddl
    3 -> V3Ddl
    4 -> V4Ddl
    5 -> V5Ddl
    else -> error("No frozen Kodex SQLite schema version $version.")
}

internal fun SqliteConnection.requireFrozenHomeSchema(version: Int) {
    check(query("PRAGMA main.application_id") == listOf(listOf(GenerationOneApplicationId))) {
        "Unrecognized Kodex SQLite database signature."
    }
    check(query("PRAGMA main.user_version") == listOf(listOf(version.toLong()))) {
        "Unexpected Kodex SQLite schema version, expected $version."
    }
    val expected = (frozenHomeDdl(version) + "CREATE TABLE sqlite_sequence(name,seq)").sorted()
    check(query("SELECT sql FROM main.sqlite_schema WHERE sql IS NOT NULL ORDER BY sql").map { it.single() } == expected) {
        "Incomplete or unexpected Kodex SQLite schema v$version."
    }
}

internal fun SqliteConnection.initializeFrozenHomeSchema(version: Int) {
    check(query("PRAGMA main.application_id") == listOf(listOf(0L)) &&
        query("PRAGMA main.user_version") == listOf(listOf(0L)) &&
        query("SELECT 1 FROM main.sqlite_schema LIMIT 1").isEmpty()
    ) { "Cannot initialize a frozen SQLite schema on a non-fresh database." }
    execute("PRAGMA main.page_size=16384")
    if (query("PRAGMA main.page_size") != listOf(listOf(16384L))) execute("VACUUM")
    transaction {
        frozenHomeDdl(version).forEach { execute(it) }
        execute("PRAGMA main.user_version=$version")
        execute("PRAGMA main.application_id=$GenerationOneApplicationId")
    }
}
