package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentsession.contract.StoredAgent
import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/** Called under the repository's connection mutex. Never changes a database's schema. */
internal class SqliteAgentRecords(private val db: SqliteConnection) {
    fun rootId(sessionId: Int): Int = requireNotNull(findRootId(sessionId)) {
        "Session $sessionId does not have a unique root Agent."
    }

    fun findRootId(sessionId: Int): Int? =
        db.query(
            "SELECT agent_id FROM agents WHERE session_id = ? AND parent_agent_id IS NULL",
            listOf(sessionId.toLong()),
        ).singleOrNull()?.single()?.agentIdInt()

    fun idsInSession(sessionId: Int): List<Int> = db.query(
        "SELECT agent_id FROM agents WHERE session_id = ?",
        listOf(sessionId.toLong()),
    ).map { it.single().agentIdInt() }

    fun get(agentId: Int): StoredAgent? = db.query(
        "SELECT agent_id, session_id, parent_agent_id, completed FROM agents WHERE agent_id = ?",
        listOf(agentId.toLong()),
    ).singleOrNull()?.toAgentRecord()

    fun createRoot(sessionId: Int): Int {
        val rootId = db.query(
            "INSERT INTO agents(session_id) VALUES (?) RETURNING agent_id",
            listOf(sessionId.toLong()),
        ).single().single().agentIdInt()
        db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES (?)", listOf(rootId.toLong()))
        return rootId
    }

    fun archiveRoot(sessionId: Int, archived: Boolean): Boolean {
        val requested = if (archived) 1L else 0L
        db.execute(
            "UPDATE agents SET completed = ? WHERE session_id = ? AND parent_agent_id IS NULL AND completed != ?",
            listOf(requested, sessionId.toLong(), requested),
        )
        return db.changes() != 0
    }

    fun createChild(parentAgentId: Int): StoredAgent {
        val row = db.query(
            """INSERT INTO agents(session_id, parent_agent_id)
                SELECT session_id, agent_id FROM agents WHERE agent_id = ?
                RETURNING agent_id, session_id, parent_agent_id, completed""",
            listOf(parentAgentId.toLong()),
        ).singleOrNull()
        return requireNotNull(row) { "Parent Agent $parentAgentId does not exist." }.toAgentRecord()
    }

    fun discardUninitializedChild(agentId: Int): Int? {
        return db.query(
            """DELETE FROM agents AS target
                WHERE target.agent_id = ? AND target.parent_agent_id IS NOT NULL AND target.completed = 0
                    AND NOT EXISTS (SELECT 1 FROM agent_commits AS c WHERE c.agent_id = target.agent_id)
                    AND NOT EXISTS (SELECT 1 FROM agents AS child WHERE child.parent_agent_id = target.agent_id)
                RETURNING session_id""",
            listOf(agentId.toLong()),
        ).singleOrNull()?.single()?.agentIdInt()
    }

    fun markChildCompleted(agentId: Int): Int? {
        return db.query(
            """UPDATE agents SET completed = 1
                WHERE agent_id = ? AND parent_agent_id IS NOT NULL AND completed = 0
                RETURNING parent_agent_id""",
            listOf(agentId.toLong()),
        ).singleOrNull()?.single()?.agentIdInt()
    }

}

private fun Any?.agentIdInt(): Int = (this as Long).toInt()

private fun List<Any?>.toAgentRecord(): StoredAgent = StoredAgent(
    agentId = this[0].agentIdInt(),
    sessionId = this[1].agentIdInt(),
    parentAgentId = this[2]?.agentIdInt(),
    completed = this[3] == 1L,
)
