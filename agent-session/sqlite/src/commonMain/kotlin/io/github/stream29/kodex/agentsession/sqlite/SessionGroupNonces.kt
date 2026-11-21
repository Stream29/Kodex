package io.github.stream29.kodex.agentsession.sqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** One repository's process-local invalidations, independent of durable group nonces. */
internal class SessionGroupNonces {
    private data class Snapshot(
        val epoch: Long = 0,
        val groupsEpoch: Long = 0,
        val pages: Map<String?, PageToken> = emptyMap(),
        val running: Map<Int, String?> = emptyMap(),
    )

    private data class PageToken(val epoch: Long, val observers: Int, val exists: Boolean)

    private val state = MutableStateFlow(Snapshot())
    val groups: Flow<Long> = state.map { it.groupsEpoch }.distinctUntilChanged()

    fun group(cwd: String?): Flow<Long> = flow {
        state.update { snapshot ->
            val old = snapshot.pages[cwd] ?: PageToken(0, 0, false)
            snapshot.copy(pages = snapshot.pages + (cwd to old.copy(observers = old.observers + 1)))
        }
        try {
            emitAll(state.map { it.pages.getValue(cwd).epoch }.distinctUntilChanged())
        } finally {
            state.update { snapshot ->
                val old = snapshot.pages.getValue(cwd)
                val pages = if (old.observers == 1 && !old.exists) snapshot.pages - cwd
                    else snapshot.pages + (cwd to old.copy(observers = old.observers - 1))
                snapshot.copy(pages = pages)
            }
        }
    }

    /** Called after commit while the new inventory and root-to-cwd mapping are still locked. */
    fun committed(affected: Map<String?, Boolean>, running: Map<Int, String?>) {
        publish(affected, running)
    }

    /**
     * A root turn transition always invalidates its row. The headers only need refreshing if a
     * group's aggregate running state changed. Neither action touches durable nonces.
     */
    fun runningChanged(running: Map<Int, String?>) {
        publish(emptyMap(), running)
    }

    private fun publish(durableChanges: Map<String?, Boolean>, running: Map<Int, String?>) {
        state.update { before ->
            val previous = before.running
            val affected = buildMap {
                if (previous != running) for (root in previous.keys + running.keys) {
                    val previouslyRunning = root in previous
                    val currentlyRunning = root in running
                    if (previouslyRunning == currentlyRunning && previous[root] == running[root]) continue
                    if (previouslyRunning) put(previous[root], true)
                    if (currentlyRunning) put(running[root], true)
                }
                // A move or deletion may remove the previous group's last member.
                putAll(durableChanges)
            }
            if (affected.isEmpty()) return@update before
            val headsChanged = durableChanges.isNotEmpty() || previous.values.toSet() != running.values.toSet()
            val next = before.epoch + 1
            Snapshot(
                epoch = next,
                groupsEpoch = if (headsChanged) next else before.groupsEpoch,
                pages = before.pages.toMutableMap().apply {
                    affected.forEach { (cwd, exists) ->
                        val observers = get(cwd)?.observers ?: 0
                        if (exists || observers > 0) put(cwd, PageToken(next, observers, exists))
                        else remove(cwd)
                    }
                },
                running = running,
            )
        }
    }
}
