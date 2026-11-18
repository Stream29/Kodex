package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentsession.contract.StoredAgent
import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/** Called under the repository's connection mutex. Never changes a database's schema. */
internal class SqliteAgentRecords(private val db: SqliteConnection) {
    // The application build can still target 0.7.2 before the separate release bump.
    // Only the migration (or standalone fresh schema setup) enables independent allocation.
    private val hasIndependentIds = db.query("PRAGMA table_info(agents)").any { it[1] == "completed" }

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
        "SELECT agent_id, session_id, parent_agent_id, ${if (hasIndependentIds) "completed" else "0"} FROM agents WHERE agent_id = ?",
        listOf(agentId.toLong()),
    ).singleOrNull()?.toAgentRecord()

    fun createRoot(sessionId: Int): Int {
        if (!hasIndependentIds) {
            db.execute(
                "INSERT INTO agents(agent_id, session_id) VALUES (?, ?)",
                listOf(sessionId.toLong(), sessionId.toLong()),
            )
            return sessionId
        }
        return db.query(
            "INSERT INTO agents(session_id) VALUES (?) RETURNING agent_id",
            listOf(sessionId.toLong()),
        ).single().single().agentIdInt()
    }

    fun createChild(parentAgentId: Int): StoredAgent {
        requireIndependentIds()
        val row = db.query(
            """INSERT INTO agents(session_id, parent_agent_id)
                SELECT session_id, agent_id FROM agents WHERE agent_id = ?
                RETURNING agent_id, session_id, parent_agent_id, completed""",
            listOf(parentAgentId.toLong()),
        ).singleOrNull()
        return requireNotNull(row) { "Parent Agent $parentAgentId does not exist." }.toAgentRecord()
    }

    fun discardUninitializedChild(agentId: Int): Int? {
        requireIndependentIds()
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
        requireIndependentIds()
        return db.query(
            """UPDATE agents SET completed = 1
                WHERE agent_id = ? AND parent_agent_id IS NOT NULL AND completed = 0
                RETURNING parent_agent_id""",
            listOf(agentId.toLong()),
        ).singleOrNull()?.single()?.agentIdInt()
    }

    private fun requireIndependentIds() {
        check(hasIndependentIds) { "Subagent storage requires the 0.7.3 Home migration." }
    }
}

private fun Any?.agentIdInt(): Int = (this as Long).toInt()

private fun List<Any?>.toAgentRecord(): StoredAgent = StoredAgent(
    agentId = this[0].agentIdInt(),
    sessionId = this[1].agentIdInt(),
    parentAgentId = this[2]?.agentIdInt(),
    completed = this[3] == 1L,
)
