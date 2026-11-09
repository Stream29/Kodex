package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

public actual fun initializeSqlitePhaseOneSchema(connection: SqliteConnection) {
    connection.transaction {
        SqlitePhaseOneSchema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

public actual fun ensureSqlitePhaseOneSchema(connection: SqliteConnection) {
    when (connection.query("PRAGMA user_version").single().single() as Long) {
        0L -> initializeSqlitePhaseOneSchema(connection)
        1L -> {
            for ((name, type) in listOf("sessions" to "table", "agents" to "table", "agent_commits" to "table", "session_catalog" to "view")) {
                check(connection.query("SELECT 1 FROM sqlite_master WHERE name = ? AND type = ?", listOf(name, type)).size == 1) {
                    "Incomplete SQLite phase one schema: $name"
                }
            }
            val hasCreatedAt = connection.query("PRAGMA table_info(agent_commits)").any { it.getOrNull(1) == "created_at" }
            if (!hasCreatedAt) {
                connection.transaction {
                    connection.execute("CREATE INDEX IF NOT EXISTS idx_sessions_archived ON sessions(archived, session_id)")
                    connection.execute("ALTER TABLE agent_commits RENAME TO agent_commits_v0_7_0")
                    connection.execute("DROP INDEX IF EXISTS commits_global_index")
                    connection.execute("DROP INDEX IF EXISTS commits_settings_cwd")
                    connection.execute("DROP VIEW IF EXISTS session_catalog")
                    SqlitePhaseOneSchema.split(';')
                        .map { it.trim() }
                        .filter {
                            it.startsWith("CREATE TABLE agent_commits") ||
                                it.startsWith("CREATE INDEX commits_") ||
                                it.startsWith("CREATE VIEW session_catalog")
                        }
                        .forEach { connection.execute(it) }
                    connection.execute(
                        """
                        INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at)
                        SELECT
                            c.agent_id,
                            c.type,
                            c."index",
                            c.payload_json,
                            coalesce(
                                (
                                    SELECT json_extract(t.payload_json, '$')
                                    FROM agent_commits_v0_7_0 AS t
                                    WHERE t.agent_id = c.agent_id
                                      AND t.type = 'timestamp'
                                      AND t."index" = c."index"
                                ),
                                (
                                    SELECT json_extract(t.payload_json, '$')
                                    FROM agent_commits_v0_7_0 AS t
                                    WHERE t.agent_id = c.agent_id
                                      AND t.type = 'timestamp'
                                      AND t."index" <= c."index"
                                    ORDER BY t."index" DESC
                                    LIMIT 1
                                ),
                                (
                                    SELECT json_extract(t.payload_json, '$')
                                    FROM agent_commits_v0_7_0 AS t
                                    WHERE t.agent_id = c.agent_id
                                      AND t.type = 'timestamp'
                                    ORDER BY t."index" ASC
                                    LIMIT 1
                                ),
                                '1970-01-01T00:00:00Z'
                            )
                        FROM agent_commits_v0_7_0 AS c
                        WHERE c.type NOT IN ('timestamp', 'compaction_point')
                        """.trimIndent(),
                    )
                    connection.execute("DROP TABLE agent_commits_v0_7_0")
                }
            }
        }
        else -> error("Unsupported SQLite schema version")
    }
}
