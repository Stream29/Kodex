package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/** Physical schema required by current repositories and the independent SQL reader. */
public const val SqliteSchemaVersion: Int = 5

/** SQLite header signature for databases prepared by the current Kodex Home format. */
public const val KodexHomeDatabaseApplicationId: Int = 0x4B445830

/** Default SQLite page size configured when initializing or rebuilding a Kodex Home database. */
public const val KodexHomeDatabasePageSize: Int = 16384

// language=SQL
// The agents DDL uses SQLite's exact ADD COLUMN spelling so existing databases can migrate in place.
private const val AgentHistorySchema: String = """

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))
, cache_nonce INTEGER NOT NULL DEFAULT 1 CHECK (cache_nonce >= 1)) STRICT;

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
"""

// language=SQL
internal const val SqliteSchema: String = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647)
) STRICT;
""" + AgentHistorySchema + """
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
"""

/**
 * Creates the entire current Kodex schema and stamps its signature in one transaction.
 * The caller owns [connection] and any staging, journal mode and durable publication.
 * @throws IllegalStateException if [connection] does not point to a fresh, unmarked main database.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure on SQLite I/O or execution failure.
 */
public expect fun initializeKodexHomeDatabase(connection: SqliteConnection)

/**
 * Verifies the current signature, schema version and complete main schema without issuing writes.
 * @throws IllegalStateException if any of them differ.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure if SQLite cannot read the database.
 */
public expect fun requireKodexHomeDatabase(connection: SqliteConnection)

/**
 * Verifies the current database and that no table, including ID sequences, contains any data.
 * Use with exclusive Home ownership to check interrupted fresh initialization.
 * @throws IllegalStateException if the database is incompatible or not empty.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure if SQLite cannot read the database.
 */
public expect fun requireEmptyKodexHomeDatabase(connection: SqliteConnection)

/** Rebuilds one existing root's projection after history was copied or reverted in this transaction. */
public expect fun SqliteConnection.recomputeSessionCatalog(rootAgentId: Int)
