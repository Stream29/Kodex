package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

internal const val CreateSessionGroupsTable = """
CREATE TABLE session_groups (
    cwd_key TEXT PRIMARY KEY,
    cwd TEXT UNIQUE,
    group_nonce INTEGER NOT NULL DEFAULT 1
) STRICT
"""

internal const val CreateSessionGroupsIndex = """
CREATE INDEX idx_session_catalog_view_cwd_updated
    ON session_catalog_view (cwd, updated_at DESC, root_agent_id DESC)
"""

internal fun SqliteConnection.requireSessionGroupsSchema() {
    check(
        query("SELECT type, ncol, wr, strict FROM pragma_table_list WHERE schema = 'main' AND name = 'session_groups'") ==
            listOf(listOf("table", 3L, 0L, 1L)) &&
            query("""SELECT name, type, "notnull", pk, dflt_value FROM pragma_table_info('session_groups') ORDER BY cid""") ==
            listOf(listOf("cwd_key", "TEXT", 1L, 1L, null), listOf("cwd", "TEXT", 0L, 0L, null),
                listOf("group_nonce", "INTEGER", 1L, 0L, "1")) &&
            query("""
                SELECT name, "desc", coll FROM pragma_index_xinfo('idx_session_catalog_view_cwd_updated')
                WHERE key = 1 ORDER BY seqno
            """) == listOf(listOf("cwd", 0L, "BINARY"), listOf("updated_at", 1L, "BINARY"),
                listOf("root_agent_id", 1L, "BINARY")) &&
            query("""
                SELECT "unique", partial FROM pragma_index_list('session_catalog_view')
                WHERE name = 'idx_session_catalog_view_cwd_updated'
            """) == listOf(listOf(0L, 0L)) &&
            query("""
                SELECT count(*) FROM pragma_index_list('session_groups') AS i
                WHERE i."unique" = 1 AND i.origin = 'u' AND i.partial = 0
                  AND (SELECT count(*) FROM pragma_index_xinfo(i.name) WHERE key = 1) = 1
                  AND EXISTS(SELECT 1 FROM pragma_index_xinfo(i.name) WHERE key = 1 AND name = 'cwd' AND coll = 'BINARY')
            """).single().single() == 1L,
    ) { "SQLite session groups require the complete 0.7.4 Kodex Home migration." }
}

public actual fun SqliteConnection.sessionCatalogGroup(rootAgentId: Int): SessionCatalogGroup? =
    query("SELECT cwd FROM session_catalog_view WHERE root_agent_id = ?", listOf(rootAgentId.toLong()))
        .singleOrNull()?.let { SessionCatalogGroup(it.single() as String?) }

public actual fun SqliteConnection.recordSessionGroupMutation(
    before: SessionCatalogGroup?, after: SessionCatalogGroup?,
): Map<String?, Boolean> = buildMap {
    for (group in setOfNotNull(before, after)) {
        val cwd = group.cwd
        require(cwd == null || cwd.isNotEmpty()) { "An empty cwd is reserved for the unset-directory key." }
        val exists = group == after || query(
            "SELECT 1 FROM session_catalog_view WHERE cwd IS ? LIMIT 1", listOf(cwd),
        ).isNotEmpty()
        if (exists) {
            execute(
                """
                INSERT INTO session_groups (cwd_key, cwd) VALUES (?, ?)
                ON CONFLICT (cwd_key) DO UPDATE SET group_nonce = group_nonce + 1
                """, listOf(cwd.orEmpty(), cwd),
            )
        } else {
            execute("DELETE FROM session_groups WHERE cwd_key = ?", listOf(cwd.orEmpty()))
        }
        put(cwd, exists)
    }
}
