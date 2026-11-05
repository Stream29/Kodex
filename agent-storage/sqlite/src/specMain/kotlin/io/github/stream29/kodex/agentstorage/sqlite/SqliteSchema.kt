package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/**
 * Authoritative SQLite DDL for Phase 1 (`PRAGMA user_version = 1`).
 */
public const val SqlitePhaseOneSchema: String = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
) STRICT;

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE agent_commits (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    timeline TEXT NOT NULL CHECK (
        timeline IN ('index', 'work', 'settings', 'timestamp', 'token_count', 'unstable')
    ),
    state_index INTEGER NOT NULL CHECK (state_index BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    PRIMARY KEY (agent_id, timeline, state_index)
) STRICT, WITHOUT ROWID;

CREATE INDEX commits_global_index ON agent_commits(agent_id, state_index, timeline);
CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, state_index DESC
) WHERE timeline = 'settings';

CREATE VIEW session_catalog (
    session_id, archived, thread_name, cwd,
    created_at, updated_at
) AS
SELECT s.session_id, s.archived,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.threadName'), '') END,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.cwd'), '.') END,
    json_extract(ct.payload_json, '$'),
    json_extract(ut.payload_json, '$')
FROM sessions AS s
LEFT JOIN agent_commits AS st
    ON st.agent_id = s.session_id AND st.timeline = 'settings'
    AND st.state_index = (
        SELECT max(state_index) FROM agent_commits
        WHERE agent_id = s.session_id AND timeline = 'settings'
    )
LEFT JOIN agent_commits AS ct
    ON ct.agent_id = s.session_id AND ct.timeline = 'timestamp'
    AND ct.state_index = 0
LEFT JOIN agent_commits AS ut
    ON ut.agent_id = s.session_id AND ut.timeline = 'timestamp'
    AND ut.state_index = (
        SELECT max(state_index) FROM agent_commits
        WHERE agent_id = s.session_id AND timeline = 'timestamp'
    );
"""

/**
 * Creates Phase 1 sessions, agents, six timelines and the ordinary session_catalog view in an empty DB.
 * Global settings remain in settings.yml. Creation is one short transaction; user_version becomes 1.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure on constraint or schema creation failure.
 */
public expect fun initializeSqlitePhaseOneSchema(connection: SqliteConnection)

public expect fun ensureSqlitePhaseOneSchema(connection: SqliteConnection)
