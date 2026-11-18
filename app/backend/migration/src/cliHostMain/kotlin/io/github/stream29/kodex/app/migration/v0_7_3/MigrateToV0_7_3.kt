package io.github.stream29.kodex.app.migration.v0_7_3

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path

internal suspend fun migrateToV0_7_3(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val database = Path(home, "kodex.db")
    if (!fileSystem.exists(database)) throw IOException("SQLite database is missing for migration to 0.7.3.")
    openSqliteConnection(database).use { connection ->
        // The database commit precedes the separate Home version checkpoint.
        connection.execute("PRAGMA synchronous=FULL")
        connection.transaction {
            connection.execute("DROP VIEW session_catalog")
            connection.execute(SessionCatalogV073)
            // SQLite defers resolution of a view's dependencies until it is queried.
            connection.query("SELECT * FROM session_catalog LIMIT 0")
        }
    }
}

// language=SQL
private const val SessionCatalogV073 = """
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
        WHERE c.agent_id = s.session_id AND c."index" = 0 ORDER BY c.type LIMIT 1),
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = s.session_id ORDER BY c."index" DESC LIMIT 1)
FROM sessions AS s
LEFT JOIN agent_commits AS st
    ON st.agent_id = s.session_id AND st.type = 'settings'
    AND st."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = s.session_id AND type = 'settings'
    )
"""
