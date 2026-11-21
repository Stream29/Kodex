package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.utils.sqlite.SqliteConnection

internal data class SqliteSessionGroupHead(val cwd: String?, val nonce: Long, val latestUpdatedAt: String?)

internal data class SqliteSessionGroupPage(
    val nonce: Long,
    /** Same ordering of columns as the root catalog entry decoder. */
    val rows: List<List<Any?>>,
    val hiddenCount: Int,
)

internal fun SqliteConnection.readSessionGroupHeads(): List<SqliteSessionGroupHead> =
    query(
        """
        SELECT g.cwd, g.group_nonce, (
            SELECT c.updated_at FROM session_catalog_view AS c
            WHERE c.cwd IS g.cwd ORDER BY c.updated_at DESC, c.root_agent_id DESC LIMIT 1
        ) AS latest
        FROM session_groups AS g ORDER BY (g.cwd IS NULL), latest DESC, g.cwd
        """,
    ).map { SqliteSessionGroupHead(it[0] as String?, it[1] as Long, it[2] as String?) }

/** Reads a single group under the owning repository's database lock. */
internal fun SqliteConnection.readSessionGroupPage(
    cwd: String?, includeArchived: Boolean, limit: Int?,
): SqliteSessionGroupPage {
    require(limit == null || limit >= 0) { "Session group limit must not be negative." }
    require(cwd == null || cwd.isNotEmpty()) { "An empty cwd is reserved for the unset-directory key." }
    val nonce = query("SELECT group_nonce FROM session_groups WHERE cwd_key = ?", listOf(cwd.orEmpty()))
        .singleOrNull()?.single() as Long?
        ?: return SqliteSessionGroupPage(0, emptyList(), 0)
    val filter = if (includeArchived) "c.cwd IS ?" else "c.cwd IS ? AND root.completed = 0"
    val from = "FROM session_catalog_view AS c JOIN agents AS root ON root.agent_id = c.root_agent_id"
    val rows = query(
        """
        SELECT root.session_id, root.completed, c.name, c.cwd, c.created_at, c.updated_at, c.root_agent_id
        $from WHERE $filter ORDER BY c.updated_at DESC, c.root_agent_id DESC
        ${if (limit == null) "" else "LIMIT ?"}
        """,
        if (limit == null) listOf(cwd) else listOf(cwd, limit.toLong()),
    )
    val hidden = if (rows.size != limit) 0 else {
        val matching = query("SELECT count(*) $from WHERE $filter", listOf(cwd)).single().single() as Long
        (matching - rows.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return SqliteSessionGroupPage(nonce, rows, hidden)
}
