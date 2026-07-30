package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Frontend history owner for one [KodexAgentState].
 *
 * This first implementation keeps a finite committed-history window. It loads the
 * committed tail first and extends the same window toward older storage
 * indexes on demand. The current request-response substate stays separate
 * from that window so replayed stream deltas never replace stable history.
 */
public class AgentHistoryViewModel internal constructor(
    public val agentState: KodexAgentState,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val commands = Channel<HistoryCommand>(Channel.UNLIMITED)
    private val mutableWindow = MutableStateFlow(AgentHistoryWindow())
    private val mutableRequestResponse = MutableStateFlow(
        agentState.state.value as? KodexAgentStateValue.RequestResponse,
    )

    public val window: StateFlow<AgentHistoryWindow> = mutableWindow.asStateFlow()
    /** Current replayable output substate, independent of the persisted history window. */
    public val requestResponse: StateFlow<KodexAgentStateValue.RequestResponse?> =
        mutableRequestResponse.asStateFlow()

    init {
        scope.launch { runHistoryLoop() }
        scope.launch {
            agentState.latestIndex.collect { latestIndex ->
                commands.send(HistoryCommand.Refresh(latestIndex))
            }
        }
        scope.launch {
            var externalWriteStart: Int? = null
            agentState.state.collect { state ->
                mutableRequestResponse.value = state as? KodexAgentStateValue.RequestResponse
                if (state == KodexAgentStateValue.ExternalWrite) {
                    if (externalWriteStart == null) {
                        externalWriteStart = agentState.latestIndex.value
                    }
                } else {
                    externalWriteStart?.let { startIndex ->
                        commands.send(
                            HistoryCommand.ExternalWriteFinished(
                                startIndex = startIndex,
                                endIndex = agentState.latestIndex.value,
                            ),
                        )
                    }
                    externalWriteStart = null
                }
            }
        }
    }

    /**
     * Requests one older batch. Repeated requests for the same boundary are
     * harmless; the history loop accepts only the first matching cursor.
     */
    public fun loadOlder() {
        val current = window.value
        val oldestIndex = current.entries.lastOrNull()?.index ?: return
        if (current.isLoading || !current.hasOlderEntries) return
        commands.trySend(
            HistoryCommand.LoadOlder(
                generation = current.generation,
                oldestIndex = oldestIndex,
            ),
        )
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun runHistoryLoop() {
        var initialized = false
        var observedLatestIndex = -1
        var generation = 0L
        var lastInvalidation: Pair<Int, Int>? = null

        suspend fun reload(
            latestIndex: Int,
            invalidate: Boolean,
        ) {
            if (invalidate) {
                generation++
                mutableWindow.value = AgentHistoryWindow(
                    generation = generation,
                    isLoading = true,
                )
            } else {
                mutableWindow.update { current ->
                    current.copy(isLoading = true, failureMessage = null)
                }
            }
            val batch = loadHistoryBatch(
                storage = agentState.storage,
                fromInclusive = latestIndex,
                limit = HistoryBatchSize,
            )
            val pending = loadPendingTail(
                storage = agentState.storage,
                snapshotIndex = latestIndex,
            )
            observedLatestIndex = latestIndex
            initialized = true
            mutableWindow.value = AgentHistoryWindow(
                generation = generation,
                entries = batch.entries,
                pending = pending,
                hasOlderEntries = batch.nextOlderIndex != null,
                isLoading = false,
            )
        }

        suspend fun refresh(latestIndex: Int) {
            if (!initialized) {
                reload(latestIndex, invalidate = false)
                return
            }
            val pending = loadPendingTail(
                storage = agentState.storage,
                snapshotIndex = latestIndex,
            )
            if (latestIndex < observedLatestIndex) {
                val invalidation = observedLatestIndex to latestIndex
                lastInvalidation = invalidation
                reload(latestIndex, invalidate = true)
                return
            }
            if (latestIndex == observedLatestIndex) {
                mutableWindow.update { current ->
                    if (current.pending == pending) current else current.copy(pending = pending)
                }
                return
            }

            val current = mutableWindow.value
            val newestLoadedIndex = current.entries.firstOrNull()?.index
            val newestStoredIndex = agentState.storage.stable.floorToIndex(latestIndex)
            observedLatestIndex = latestIndex
            if (newestStoredIndex == null || newestStoredIndex == newestLoadedIndex) {
                mutableWindow.update { window ->
                    if (window.pending == pending) window else window.copy(pending = pending)
                }
                return
            }
            if (newestLoadedIndex == null) {
                reload(latestIndex, invalidate = false)
                return
            }

            val additions = mutableListOf<AgentHistoryStoredEntry>()
            var index: Int? = newestStoredIndex
            while (index != null && index > newestLoadedIndex) {
                additions += AgentHistoryStoredEntry(
                    index = index,
                    event = agentState.storage.stable[index],
                )
                index = agentState.storage.stable.prevIndex(index)
            }
            if (additions.isNotEmpty()) {
                mutableWindow.update { window ->
                    window.copy(
                        entries = additions + window.entries,
                        pending = pending,
                        failureMessage = null,
                    )
                }
            }
        }

        suspend fun loadOlder(command: HistoryCommand.LoadOlder) {
            val current = mutableWindow.value
            if (
                current.generation != command.generation ||
                current.entries.lastOrNull()?.index != command.oldestIndex ||
                !current.hasOlderEntries
            ) {
                return
            }

            mutableWindow.value = current.copy(isLoading = true, failureMessage = null)
            val firstOlderIndex = agentState.storage.stable.prevIndex(command.oldestIndex)
            if (firstOlderIndex == null) {
                mutableWindow.value = current.copy(
                    hasOlderEntries = false,
                    isLoading = false,
                    failureMessage = null,
                )
                return
            }
            val batch = loadHistoryBatch(
                storage = agentState.storage,
                fromInclusive = firstOlderIndex,
                limit = HistoryBatchSize,
            )
            val latest = mutableWindow.value
            if (
                latest.generation == command.generation &&
                latest.entries.lastOrNull()?.index == command.oldestIndex
            ) {
                mutableWindow.value = latest.copy(
                    entries = latest.entries + batch.entries,
                    hasOlderEntries = batch.nextOlderIndex != null,
                    isLoading = false,
                    failureMessage = null,
                )
            }
        }

        for (command in commands) {
            try {
                when (command) {
                    is HistoryCommand.Refresh -> refresh(command.latestIndex)
                    is HistoryCommand.LoadOlder -> loadOlder(command)
                    is HistoryCommand.ExternalWriteFinished -> {
                        val invalidation = command.startIndex to command.endIndex
                        if (
                            command.endIndex <= command.startIndex &&
                            invalidation != lastInvalidation
                        ) {
                            lastInvalidation = invalidation
                            reload(command.endIndex, invalidate = true)
                        } else {
                            refresh(command.endIndex)
                        }
                        // This pair only de-duplicates the latestIndex emission
                        // belonging to this external-write transition. A later
                        // same-index replacement must invalidate again.
                        lastInvalidation = null
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                mutableWindow.update { current ->
                    current.copy(
                        isLoading = false,
                        failureMessage = failure.message ?: failure.toString(),
                    )
                }
            }
        }
    }
}

/** Wraps one AgentState in an independently disposable history frontend. */
public fun AgentHistoryViewModel(agentState: KodexAgentState): AgentHistoryViewModel =
    AgentHistoryViewModel(
        agentState = agentState,
        scope = agentState.supervisorChildScope(),
    )

private sealed interface HistoryCommand {
    data class Refresh(
        val latestIndex: Int,
    ) : HistoryCommand

    data class LoadOlder(
        val generation: Long,
        val oldestIndex: Int,
    ) : HistoryCommand

    data class ExternalWriteFinished(
        val startIndex: Int,
        val endIndex: Int,
    ) : HistoryCommand
}

private const val HistoryBatchSize: Int = 64
