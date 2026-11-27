package io.github.stream29.kodex.agentsession.sqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** One repository's process-local invalidations, independent of durable group nonces. */
internal class SessionGroupNonces(initialNonces: Map<String?, Long>) {
    private data class Snapshot(
        val epoch: Long = 0,
        val groupsEpoch: Long = 0,
        val pages: Map<String?, PageToken> = emptyMap(),
        val nonces: Map<String?, Long>,
        val runningRows: Map<Int, String?> = emptyMap(),
        val runningGroups: Set<String?> = emptySet(),
    )

    private data class PageToken(val epoch: Long, val observers: Int)

    private val state = MutableStateFlow(Snapshot(nonces = initialNonces))
    val groups: Flow<Long> = state.map { it.groupsEpoch }.distinctUntilChanged()

    fun group(cwd: String?): Flow<Long> = flow {
        state.update { snapshot ->
            val old = snapshot.pages[cwd] ?: PageToken(0, 0)
            snapshot.copy(pages = snapshot.pages + (cwd to old.copy(observers = old.observers + 1)))
        }
        try {
            emitAll(state.map { it.pages.getValue(cwd).epoch }.distinctUntilChanged())
        } finally {
            state.update { snapshot ->
                val old = snapshot.pages.getValue(cwd)
                val pages = if (old.observers == 1 && cwd !in snapshot.nonces) snapshot.pages - cwd
                    else snapshot.pages + (cwd to old.copy(observers = old.observers - 1))
                snapshot.copy(pages = pages)
            }
        }
    }

    /** Called after commit while the new inventory and root-to-cwd mapping are still locked. */
    fun committed(affected: Map<String?, Long?>, running: Map<Int, String?>) {
        state.update { before ->
            val nonces = before.nonces.toMutableMap()
            for ((cwd, nonce) in affected) {
                if (nonce == null) nonces.remove(cwd) else nonces[cwd] = nonce
            }
            before.advance(nonces, affected.keys, running, invalidateRows = true)
        }
    }

    /**
     * A root turn transition always invalidates its row. The headers only need refreshing if a
     * group's aggregate running state changed. Neither action touches durable nonces.
     */
    fun runningChanged(running: Map<Int, String?>) {
        state.update { it.advance(it.nonces, emptySet(), running, invalidateRows = true) }
    }

    /** A remote signal is a hint, not a mutation. Invalidate only differences found in SQLite or the running projection. */
    fun reconcile(nonces: Map<String?, Long>, running: Map<Int, String?>) {
        state.update { before ->
            val candidates = before.nonces.keys + nonces.keys
            before.advance(nonces, candidates, running, invalidateRows = false)
        }
    }

    private fun Snapshot.advance(
        durable: Map<String?, Long>,
        candidates: Set<String?>,
        running: Map<Int, String?>,
        invalidateRows: Boolean,
    ): Snapshot {
        val nextRunningGroups = running.values.toSet()
        val changedHeads = (candidates.filter { nonces[it] != durable[it] } +
            runningGroups.filterNot { it in nextRunningGroups } +
            nextRunningGroups.filterNot { it in runningGroups }).toSet()
        val affected = buildSet {
            addAll(changedHeads)
            if (invalidateRows && runningRows != running) for (root in runningRows.keys + running.keys) {
                if ((root in runningRows) == (root in running) && runningRows[root] == running[root]) continue
                if (root in runningRows) add(runningRows[root])
                if (root in running) add(running[root])
            }
        }
        if (affected.isEmpty()) return this
        val next = epoch + 1
        return copy(
            epoch = next,
            groupsEpoch = if (changedHeads.isNotEmpty()) next else groupsEpoch,
            pages = pages.toMutableMap().apply {
                for (cwd in affected) {
                    val observers = get(cwd)?.observers ?: 0
                    if (cwd in durable || observers > 0) put(cwd, PageToken(next, observers)) else remove(cwd)
                }
            },
            nonces = durable,
            runningRows = if (invalidateRows) running else runningRows,
            runningGroups = nextRunningGroups,
        )
    }
}
