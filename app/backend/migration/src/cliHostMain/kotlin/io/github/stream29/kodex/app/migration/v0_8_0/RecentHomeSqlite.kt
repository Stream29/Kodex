package io.github.stream29.kodex.app.migration.v0_8_0

import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.recomputeSessionCatalog
import io.github.stream29.kodex.agentstorage.sqlite.requireKodexHomeDatabase
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.io.files.Path

/** Checks the complete physical schema without issuing writes. */
internal fun requireRecentHomeSourceSchema(connection: SqliteConnection) {
    connection.requireRecentHomeSourceSchemaAt("main")
}

/**
 * Copies a private, coordinator-owned legacy snapshot into an empty staging database.
 * Schema initialization commits separately; all copied and derived rows commit together.
 * On failure the coordinator must discard the staging database.
 */
internal fun upgradeRecentHomeDatabase(target: SqliteConnection, sourceSnapshot: Path) {
    check(target.query("PRAGMA foreign_keys") == listOf(listOf(1L))) {
        "Recent Home migration requires SQLite foreign-key enforcement."
    }
    initializeKodexHomeDatabase(target)
    target.execute("ATTACH DATABASE ? AS recent_source", listOf(sourceSnapshot.toString()))
    var primary: Throwable? = null
    try {
        target.transaction {
            target.requireRecentHomeSourceSchemaAt("recent_source")
            target.requireRecentHomeIntegrity("recent_source")
            target.requireRecentHomeAgentTree()
            target.copyRecentHomeRows()
            backfillRecentHomeResponseTps(target)
            normalizeRecentHomeHistory(target)
            target.buildRecentHomeCatalog()
            for (table in listOf("sessions", "agents", "agent_commits", "schedules", "sqlite_sequence")) {
                check(target.query("SELECT count(*) FROM main.$table") ==
                    target.query("SELECT count(*) FROM recent_source.$table")) {
                    "Unexpected row count after copying recent Home $table."
                }
            }
            requireKodexHomeDatabase(target)
            target.requireRecentHomeIntegrity("main")
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            target.execute("DETACH DATABASE recent_source")
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }
}

private fun SqliteConnection.requireRecentHomeSourceSchemaAt(schema: String) {
    check(query("PRAGMA $schema.application_id") == listOf(listOf(0L)) &&
        query("PRAGMA $schema.user_version") == listOf(listOf(1L))) {
        "Unrecognized recent Kodex SQLite database signature or schema version."
    }
    check(query("SELECT sql FROM $schema.sqlite_schema ORDER BY sql").map { it.single() } == RecentHomeSourceSql) {
        "Incomplete or unexpected recent Kodex SQLite schema."
    }
}

private fun SqliteConnection.requireRecentHomeIntegrity(schema: String) {
    check(query("PRAGMA $schema.integrity_check(1)") == listOf(listOf("ok"))) {
        "SQLite integrity check failed for recent Home $schema."
    }
    check(query("PRAGMA $schema.foreign_key_check").isEmpty()) {
        "SQLite foreign-key check failed for recent Home $schema."
    }
}

private fun SqliteConnection.requireRecentHomeAgentTree() {
    // The legacy partial unique index already guarantees at most one root per session.
    check(query(
        """
        SELECT s.session_id FROM recent_source.sessions AS s
        WHERE NOT EXISTS (
            SELECT 1 FROM recent_source.agents AS a
            WHERE a.session_id = s.session_id AND a.parent_agent_id IS NULL
        ) LIMIT 1
        """.trimIndent(),
    ).isEmpty()) { "A recent Home session has no root agent." }
    // An agent has only one parent. A cycle cannot be reached from a NULL-parent root.
    // Restricting every edge to its own session also detects cross-session parent links.
    check(query(
        """
        WITH RECURSIVE reachable(agent_id, session_id) AS (
            SELECT agent_id, session_id FROM recent_source.agents WHERE parent_agent_id IS NULL
            UNION ALL
            SELECT a.agent_id, a.session_id FROM recent_source.agents AS a
            JOIN reachable AS p ON a.parent_agent_id = p.agent_id AND a.session_id = p.session_id
        )
        SELECT 1 WHERE (SELECT count(*) FROM reachable) <>
                       (SELECT count(*) FROM recent_source.agents)
        """.trimIndent(),
    ).isEmpty()) { "A recent Home agent is disconnected from the root of its session." }
}

private fun SqliteConnection.copyRecentHomeRows() {
    execute("INSERT INTO main.sessions (session_id) SELECT session_id FROM recent_source.sessions")
    // Archive status belonged to sessions, not to the legacy root's completed flag.
    execute(
        """
        INSERT INTO main.agents (agent_id, session_id, parent_agent_id, completed)
        SELECT a.agent_id, a.session_id, a.parent_agent_id,
               CASE WHEN a.parent_agent_id IS NULL THEN s.archived ELSE a.completed END
        FROM recent_source.agents AS a JOIN recent_source.sessions AS s USING (session_id)
        """.trimIndent(),
    )
    execute(
        """
        INSERT INTO main.agent_commits (agent_id, type, "index", payload_json, created_at)
        SELECT agent_id, type, "index", payload_json, created_at FROM recent_source.agent_commits
        """.trimIndent(),
    )
    execute(
        """
        INSERT INTO main.schedules (
            schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at
        )
        SELECT schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at
        FROM recent_source.schedules
        """.trimIndent(),
    )
    // Explicit inserts seed maxima for surviving rows, not the high-water marks of deleted IDs.
    execute("DELETE FROM main.sqlite_sequence")
    execute(
        """
        INSERT INTO main.sqlite_sequence (rowid, name, seq)
        SELECT rowid, name, seq FROM recent_source.sqlite_sequence
        """.trimIndent(),
    )
}

private fun SqliteConnection.buildRecentHomeCatalog() {
    execute(
        """
        INSERT INTO main.session_catalog_view (root_agent_id)
        SELECT agent_id FROM main.agents WHERE parent_agent_id IS NULL
        """.trimIndent(),
    )
    // Reuse the current projection, including its fixed nine-digit UTC activity timestamp.
    // Bound the materialized key list independently of the number of sessions.
    var after = -1L
    while (true) {
        val roots = query(
            """
            SELECT root_agent_id FROM main.session_catalog_view
            WHERE root_agent_id > ? ORDER BY root_agent_id LIMIT 256
            """.trimIndent(),
            listOf(after),
        )
        if (roots.isEmpty()) break
        for ((root) in roots) recomputeSessionCatalog((root as Long).toInt())
        after = roots.last().single() as Long
    }
    check(query("SELECT 1 FROM main.session_catalog_view WHERE cwd = '' LIMIT 1").isEmpty()) {
        "An empty recent Home catalog cwd conflicts with the unset-directory group."
    }
    execute(
        """
        INSERT INTO main.session_groups (cwd_key, cwd)
        SELECT DISTINCT coalesce(cwd, ''), cwd FROM main.session_catalog_view
        """.trimIndent(),
    )
}

// The public 0.7.3 and 0.7.5 checkpoints share this dump. The 0.7.2 preparation
// creates schedules on both paths, and 0.7.3 adds completed via ALTER TABLE,
// then replaces the allocator clause and installs the root-aware catalog view.
internal val RecentHomeSourceDdl: List<String> = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
) STRICT;

CREATE INDEX idx_sessions_archived ON sessions(archived, session_id);

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE
, completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))) STRICT;

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
) STRICT, WITHOUT ROWID;

CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type);

CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, "index" DESC
) WHERE type = 'settings';

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

CREATE VIEW session_catalog (
    session_id, archived, thread_name, cwd,
    created_at, updated_at
) AS
SELECT s.session_id, s.archived,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.threadName'), '') END,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.cwd'), '.') END,
    (SELECT c.created_at FROM agent_commits AS c INDEXED BY commits_global_index
        WHERE c.agent_id = root.agent_id AND c."index" = 0 ORDER BY c.type LIMIT 1),
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = root.agent_id ORDER BY c."index" DESC LIMIT 1)
FROM sessions AS s
LEFT JOIN agents AS root ON root.session_id = s.session_id AND root.parent_agent_id IS NULL
LEFT JOIN agent_commits AS st
    ON st.agent_id = root.agent_id AND st.type = 'settings'
    AND st."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = root.agent_id AND type = 'settings'
    );
""".split(';').map(String::trim).filter(String::isNotEmpty)

private val RecentHomeSourceSql = (RecentHomeSourceDdl + "CREATE TABLE sqlite_sequence(name,seq)").sorted()
