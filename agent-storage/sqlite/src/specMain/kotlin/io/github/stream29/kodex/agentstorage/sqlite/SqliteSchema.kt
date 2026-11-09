package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/**
 * Authoritative SQLite DDL for Phase 1 (`PRAGMA user_version = 1`).
 */
// language=SQL
public const val SqlitePhaseOneSchema: String = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
) STRICT;

CREATE INDEX idx_sessions_archived ON sessions(archived, session_id);

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE agent_commits (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (
        type IN (
            'user_message',
            'assistant_message',
            'assistant_message_commentary',
            'assistant_message_final',
            'developer_message',
            'agent_message',
            'request_user_input_tool_event',
            'suggest_subagent_task_tool_event',
            'plan_update',
            'compaction_point',
            'reasoning',
            'context_compaction',
            'command_execution_tool_event',
            'patch_tool_event',
            'mcp_tool_event',
            'web_search_tool_event',
            'image_generation_tool_event',
            'image_view_tool_event',
            'tool_search_event',
            'invalid_tool_call',
            'server_tool_search',
            'hosted_web_search',
            'hosted_image_generation',
            'json_tool_event',
            'text_tool_event',
            'custom_tool_event',
            'settings',
            'timestamp',
            'llm_request_meta_legacy',
            'llm_request_meta_initialization',
            'llm_request_meta_compaction',
            'llm_request_meta_response',
            'token_count_legacy',
            'token_count_initialization',
            'token_count_compaction',
            'token_count_response',
            'unstable'
        )
    ),
    "index" INTEGER NOT NULL CHECK ("index" BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, type, "index")
) STRICT, WITHOUT ROWID;

CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type);
CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, "index" DESC
) WHERE type = 'settings';

CREATE VIEW session_catalog (
    session_id, archived, thread_name, cwd,
    created_at, updated_at
) AS
SELECT s.session_id, s.archived,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.threadName'), '') END,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.cwd'), '.') END,
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = s.session_id AND c."index" = 0 LIMIT 1),
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = s.session_id ORDER BY c."index" DESC LIMIT 1)
FROM sessions AS s
LEFT JOIN agent_commits AS st
    ON st.agent_id = s.session_id AND st.type = 'settings'
    AND st."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = s.session_id AND type = 'settings'
    );
"""

/**
 * Creates Phase 1 sessions, agents, six timelines and the ordinary session_catalog view in an empty DB.
 * Global settings remain in settings.yml. Creation is one short transaction; user_version becomes 1.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure on constraint or schema creation failure.
 */
public expect fun initializeSqlitePhaseOneSchema(connection: SqliteConnection)

public expect fun ensureSqlitePhaseOneSchema(connection: SqliteConnection)
