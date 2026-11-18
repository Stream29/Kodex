package io.github.stream29.kodex.app.migration.v0_7_3

import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.io.IOException

/** Runs after the catalog optimization in the same versioned database transaction. */
internal fun migrateSubagentAgentIds(db: SqliteConnection) {
    val columns = db.query("PRAGMA table_info(agents)").map { it[1] }
    if ("completed" !in columns) {
        db.execute("ALTER TABLE agents ADD COLUMN completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))")
    }
    // In the 0.7.2 schema the AUTOINCREMENT schedules table already owns sqlite_sequence.
    if (db.query("SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'sqlite_sequence'").isEmpty()) {
        throw IOException("Cannot migrate AgentId allocation: 0.7.2 sqlite_sequence is missing.")
    }
    val agents = db.tableDefinition("agents")
    val commits = db.tableDefinition("agent_commits")
    val nextAgents = agents.withClause(
        "agent_id INTEGER PRIMARY KEY",
        "agent_id INTEGER PRIMARY KEY AUTOINCREMENT",
    )
    val nextCommits = commits.withClause(
        "'event_terminal_exited',",
        "'event_terminal_exited',\n            'event_subagent_created',\n            'event_subagent_completed',",
    )
    if (agents != nextAgents || commits != nextCommits) {
        // Neither table is copied: retain its b-tree, rowids, indexes, and inbound foreign keys.
        db.execute("PRAGMA writable_schema = ON")
        try {
            db.execute("UPDATE sqlite_schema SET sql = ? WHERE type = 'table' AND name = 'agents'", listOf(nextAgents))
            db.execute("UPDATE sqlite_schema SET sql = ? WHERE type = 'table' AND name = 'agent_commits'", listOf(nextCommits))
            val schemaVersion = db.query("PRAGMA schema_version").single().single() as Long
            db.execute("PRAGMA schema_version = ${schemaVersion + 1}")
        } finally {
            db.execute("PRAGMA writable_schema = RESET")
        }
    }
    // Re-entry (commit succeeded; Home checkpoint did not) must not lower the high-water mark.
    db.execute(
        """INSERT INTO sqlite_sequence(name, seq)
            SELECT 'agents', coalesce((SELECT max(agent_id) FROM agents), 0)
            WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'agents')""",
    )
    db.execute(
        """UPDATE sqlite_sequence SET seq = max(seq, coalesce((SELECT max(agent_id) FROM agents), 0))
            WHERE name = 'agents'""",
    )
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agents_root_session ON agents(session_id) WHERE parent_agent_id IS NULL")
    db.execute("DROP VIEW session_catalog")
    db.execute(SessionCatalogByRootV073)
    db.query("SELECT * FROM session_catalog LIMIT 0")
    if (db.query("SELECT 1 FROM pragma_foreign_key_check LIMIT 1").isNotEmpty()) {
        throw IOException("Foreign key violation after the 0.7.3 AgentId migration.")
    }
}

private fun SqliteConnection.tableDefinition(name: String): String =
    query("SELECT sql FROM sqlite_schema WHERE type = 'table' AND name = ?", listOf(name))
        .singleOrNull()?.singleOrNull() as? String
        ?: throw IOException("Cannot migrate AgentId allocation: missing table $name.")

private fun String.withClause(original: String, replacement: String): String = when {
    replacement in this -> this
    indexOf(original) >= 0 && indexOf(original) == lastIndexOf(original) -> replace(original, replacement)
    else -> throw IOException("Cannot migrate AgentId allocation: unsupported SQLite definition ($original).")
}

// Keep the SQL view's existing public columns; use the root relation internally.
// language=SQL
private const val SessionCatalogByRootV073 = """
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
    )
"""
