package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.app.history.contract.AgentHistoryCursor
import io.github.stream29.kodex.app.history.contract.AgentHistoryDirection
import io.github.stream29.kodex.app.history.contract.AgentHistoryEdgeState
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntryKey
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadRequest
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowSnapshot
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/** Finite newest-first committed-history projection for one Agent. */
internal class AgentHistoryViewModelImpl(
    private val agentState: KodexAgentState,
    private val scope: CoroutineScope,
) : AgentHistoryViewModel {
    private val commands = Channel<HistoryCommand>(Channel.UNLIMITED)
    private val mutableWindow = MutableStateFlow(AgentHistoryWindowSnapshot())

    override val window: StateFlow<AgentHistoryWindowSnapshot> = mutableWindow.asStateFlow()

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

    override fun request(request: AgentHistoryLoadRequest) {
        commands.trySend(HistoryCommand.Load(request))
    }

    override fun close() {
        commands.close()
        scope.cancel()
    }

    private suspend fun runHistoryLoop() {
        var initialized = false
        var observedLatestIndex = -1
        var generation = 0L
        var lastInvalidation: Pair<Int, Int>? = null

        suspend fun reload(latestIndex: Int, invalidate: Boolean) {
            if (invalidate) {
                check(generation < Long.MAX_VALUE) { "History generations are exhausted." }
                generation += 1
            }
            mutableWindow.value = AgentHistoryWindowSnapshot(
                generation = generation,
                status = AgentHistoryWindowStatus.Initializing,
            )
            val batch = loadBatch(latestIndex, HistoryBatchSize)
            observedLatestIndex = latestIndex
            initialized = true
            mutableWindow.value = AgentHistoryWindowSnapshot(
                generation = generation,
                entries = batch.entries,
                olderEdge = batch.nextOlderIndex.toOlderEdge(generation),
                newerEdge = AgentHistoryEdgeState.Exhausted,
                status = AgentHistoryWindowStatus.Ready,
            )
        }

        suspend fun refresh(latestIndex: Int) {
            if (!initialized) {
                reload(latestIndex, invalidate = false)
                return
            }
            if (latestIndex < observedLatestIndex) {
                lastInvalidation = observedLatestIndex to latestIndex
                reload(latestIndex, invalidate = true)
                return
            }
            if (latestIndex == observedLatestIndex) return

            val current = mutableWindow.value
            val newestLoaded = current.entries.firstOrNull()?.key?.primaryStorageIndex
            val newestStored = agentState.storage.stable.floorToIndex(latestIndex)
            observedLatestIndex = latestIndex
            if (newestStored == null || newestStored == newestLoaded) return
            if (newestLoaded == null) {
                reload(latestIndex, invalidate = false)
                return
            }
            val additions = mutableListOf<AgentHistoryEntry>()
            var index: Int? = newestStored
            while (index != null && index > newestLoaded) {
                additions += entryAt(index)
                index = agentState.storage.stable.prevIndex(index)
            }
            if (additions.isNotEmpty()) {
                mutableWindow.value = current.copy(
                    entries = additions + current.entries,
                    newerEdge = AgentHistoryEdgeState.Exhausted,
                    status = AgentHistoryWindowStatus.Ready,
                )
            }
        }

        suspend fun load(request: AgentHistoryLoadRequest) {
            if (request.cursor.direction != AgentHistoryDirection.Older) return
            val current = mutableWindow.value
            val ready = current.olderEdge as? AgentHistoryEdgeState.Ready ?: return
            if (
                ready.cursor != request.cursor ||
                request.cursor.generation != current.generation
            ) {
                return
            }
            mutableWindow.value = current.copy(
                olderEdge = AgentHistoryEdgeState.Loading(request.cursor),
            )
            try {
                val firstIndex = agentState.storage.stable.prevIndex(
                    request.cursor.storageIndexExclusive,
                )
                if (firstIndex == null) {
                    mutableWindow.value = current.copy(olderEdge = AgentHistoryEdgeState.Exhausted)
                    return
                }
                val batch = loadBatch(firstIndex, request.itemBudget)
                val latest = mutableWindow.value
                if (
                    latest.generation != request.cursor.generation ||
                    latest.olderEdge != AgentHistoryEdgeState.Loading(request.cursor)
                ) {
                    return
                }
                mutableWindow.value = latest.copy(
                    entries = latest.entries + batch.entries,
                    olderEdge = batch.nextOlderIndex.toOlderEdge(latest.generation),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val latest = mutableWindow.value
                if (
                    latest.generation == request.cursor.generation &&
                    latest.olderEdge == AgentHistoryEdgeState.Loading(request.cursor)
                ) {
                    mutableWindow.value = latest.copy(
                        olderEdge = AgentHistoryEdgeState.Failed(
                            cursor = request.cursor,
                            message = failure.message ?: failure.toString(),
                        ),
                    )
                }
            }
        }

        for (command in commands) {
            try {
                when (command) {
                    is HistoryCommand.Refresh -> refresh(command.latestIndex)
                    is HistoryCommand.Load -> load(command.request)
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
                        lastInvalidation = null
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                mutableWindow.value = mutableWindow.value.copy(
                    status = AgentHistoryWindowStatus.Failed(
                        failure.message ?: failure.toString(),
                    ),
                )
            }
        }
    }

    private suspend fun loadBatch(
        fromInclusive: Int,
        limit: Int,
    ): LoadedHistoryBatch {
        require(limit > 0) { "History batch size must be positive." }
        if (fromInclusive < 0) return LoadedHistoryBatch(emptyList(), null)
        val entries = ArrayList<AgentHistoryEntry>(limit)
        var index = agentState.storage.stable.floorToIndex(fromInclusive)
        while (index != null && entries.size < limit) {
            entries += entryAt(index)
            index = agentState.storage.stable.prevIndex(index)
        }
        return LoadedHistoryBatch(entries, index)
    }

    private suspend fun entryAt(index: Int): AgentHistoryEntry =
        AgentHistoryEntry(
            key = AgentHistoryEntryKey(primaryStorageIndex = index),
            event = agentState.storage.stable[index],
        )
}

/** Creates the history projection owned by one materialized Agent ViewModel. */
public fun createAgentHistoryViewModel(
    agentState: KodexAgentState,
    ownerScope: CoroutineScope,
): AgentHistoryViewModel = AgentHistoryViewModelImpl(
    agentState = agentState,
    scope = ownerScope,
)

/** Koin-resolved history creator with one exact Agent runtime parameter set. */
@Factory
public class DefaultAgentHistoryViewModelFactory(
    @InjectedParam private val agentState: KodexAgentState,
    @InjectedParam private val ownerScope: CoroutineScope,
) {
    public fun create(): AgentHistoryViewModel =
        createAgentHistoryViewModel(agentState, ownerScope)
}

private data class LoadedHistoryBatch(
    val entries: List<AgentHistoryEntry>,
    val nextOlderIndex: Int?,
)

private fun Int?.toOlderEdge(generation: Long): AgentHistoryEdgeState =
    this?.let { nextOlderIndex ->
        AgentHistoryEdgeState.Ready(
            AgentHistoryCursor(
                generation = generation,
                storageIndexExclusive = nextOlderIndex + 1,
                direction = AgentHistoryDirection.Older,
            ),
        )
    } ?: AgentHistoryEdgeState.Exhausted

private sealed interface HistoryCommand {
    data class Refresh(val latestIndex: Int) : HistoryCommand
    data class Load(val request: AgentHistoryLoadRequest) : HistoryCommand
    data class ExternalWriteFinished(val startIndex: Int, val endIndex: Int) : HistoryCommand
}

private const val HistoryBatchSize: Int = 64
