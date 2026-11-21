package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentstate.impl.ForkContextPhase
import io.github.stream29.kodex.agentstate.impl.ForkContextRecovery
import io.github.stream29.kodex.agentstate.impl.StablePhaseTransitionTypes
import io.github.stream29.kodex.agentstate.impl.stablePhaseAt
import io.github.stream29.kodex.agentstate.impl.startsNewTurnAt
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.utils.sqlite.SqliteConnection

/** The public commit schema and mirrors do not carry recovery data. Use only under the DB mutex. */
internal class SqliteForkContextRecovery(private val connection: SqliteConnection) {
    fun hasRecovery(agentId: Int): Boolean = read(agentId, Int.MAX_VALUE) != null

    fun read(agentId: Int, upToIndex: Int): ForkContextRecovery? {
        if (upToIndex < 0) return null
        return connection.query(
            """SELECT checkpoint_index, phase, starts_new_turn FROM fork_context_recovery
                WHERE agent_id = ? AND checkpoint_index <= ? ORDER BY checkpoint_index DESC LIMIT 1""",
            listOf(agentId.toLong(), upToIndex.toLong()),
        ).singleOrNull()?.let { (index, phase, startsNewTurn) ->
            ForkContextRecovery((index as Long).toInt(), ForkContextPhase.valueOf(phase as String), startsNewTurn == 1L)
        }
    }

    fun capture(agentId: Int, checkpoint: Int): ForkContextRecovery {
        val before = checkpoint - 1
        val old = read(agentId, checkpoint)
        if (old?.checkpointIndex == checkpoint) return old
        return ForkContextRecovery(
            checkpoint,
            stablePhaseAt(lastTransition(agentId, before, StablePhaseTransitionTypes), old),
            startsNewTurnAt(lastTransition(agentId, before, AgentCommitType.TurnStartBoundaryTypes), old),
        )
    }

    /** Called inside the copy transaction, after the target checkpoint was inserted. */
    fun insert(agentId: Int, recovery: ForkContextRecovery) {
        check(connection.query("PRAGMA foreign_keys").single().single() == 1L) {
            "Fork recovery requires SQLite foreign-key enforcement."
        }
        connection.execute(
            """INSERT INTO fork_context_recovery (agent_id, checkpoint_index, phase, starts_new_turn) VALUES (?, ?, ?, ?)""",
            listOf(agentId.toLong(), recovery.checkpointIndex.toLong(), recovery.phase.name, if (recovery.startsNewTurn) 1L else 0L),
        )
    }

    private fun lastTransition(agentId: Int, upTo: Int, types: Set<AgentCommitType>): Pair<Int, AgentCommitType>? {
        if (upTo < 0) return null
        val placeholders = types.joinToString(",") { "?" }
        val candidates = connection.query(
            """SELECT "index", type FROM agent_commits WHERE agent_id = ? AND "index" <= ?
                AND type IN ($placeholders) ORDER BY "index" DESC LIMIT ?""",
            listOf(agentId.toLong(), upTo.toLong()) + types.map { it.wireName } + types.size.toLong(),
        )
        val byWire = types.associateBy { it.wireName }
        return candidates.map { (index, type) -> (index as Long).toInt() to byWire.getValue(type as String) }
            .maxWithOrNull(compareBy({ it.first }, { it.second.ordinal }))
    }
}
