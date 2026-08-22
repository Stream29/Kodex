package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.nextIndex
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Newest-first, demand-extended History View state for one Agent. */
internal class AgentHistoryViewModelImpl(
    private val agentState: KodexAgentState,
    private val scope: CoroutineScope,
    private val runningTurn: StateFlow<Job?>,
) : AgentHistoryViewModel {
    private val commands = Channel<HistoryCommand>(capacity = Channel.BUFFERED)
    private val historyReadSemaphore = Semaphore(HistoryReadParallelism)
    private val olderDemandPending = MutableStateFlow(false)
    private val timelineCursor = HistoryTimelineCursor(agentState)
    private val mutableHistoryItems = MutableStateFlow(
        HistoryItemWindowImpl(
            generation = 0,
            sequence = HistorySequence.Empty,
            onOlderDemand = ::registerOlderDemand,
        ),
    )
    private val mutableLoadState =
        MutableStateFlow<AgentHistoryLoadState>(AgentHistoryLoadState.Initializing)
    private val mutablePendingTools = MutableStateFlow<List<UnstableCleanEvent>>(emptyList())
    private val mutableStreamingItem = MutableStateFlow<HistoryStreamingItem?>(null)
    private val mutableLatestTurnTimeMarker =
        MutableStateFlow<HistoryItemViewModel.TurnTimeMarker?>(null)
    private val mutableActiveTurnDuration = MutableStateFlow<Duration?>(null)

    override val historyItems: StateFlow<HistoryItemWindow> =
        mutableHistoryItems.asStateFlow()
    override val loadState: StateFlow<AgentHistoryLoadState> = mutableLoadState.asStateFlow()
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> =
        mutablePendingTools.asStateFlow()
    override val streamingItem: StateFlow<HistoryStreamingItem?> =
        mutableStreamingItem.asStateFlow()
    override val activeTurnDuration: StateFlow<Duration?> =
        mutableActiveTurnDuration.asStateFlow()

    override val listState: LazyListState = LazyListState()
    override val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource(::onScrollInteraction)

    private var mutableFollowsLatest: Boolean by mutableStateOf(true)
    override val followsLatest: Boolean
        get() = mutableFollowsLatest

    init {
        scope.launch { runHistoryLoop() }
        scope.launch {
            agentState.latestIndex.collect { latestIndex ->
                commands.send(HistoryCommand.Refresh(latestIndex))
            }
        }
        scope.launch {
            agentState.latestIndex.collect { latestIndex ->
                publishPendingTools(loadPendingTools(latestIndex))
            }
        }
        scope.launch {
            var ticker: Job? = null
            try {
                runningTurn.collect { turnJob ->
                    ticker?.cancel()
                    ticker = turnJob?.let { parent ->
                        CoroutineScope(scope.coroutineContext.minusKey(Job) + parent).launch {
                            try {
                                commands.send(HistoryCommand.UpdateLatestTurn(active = true))
                                while (true) {
                                    delay(1_000)
                                    commands.send(HistoryCommand.UpdateLatestTurn(active = true))
                                }
                            } finally {
                                commands.trySend(HistoryCommand.UpdateLatestTurn(active = false))
                            }
                        }
                    }
                    if (turnJob == null) {
                        commands.send(HistoryCommand.UpdateLatestTurn(active = false))
                    }
                }
            } finally {
                ticker?.cancel()
            }
        }
        scope.launch {
            var externalWriteStart: Int? = null
            agentState.state.collect { state ->
                publishStreamingItem(state.toStreamingItem())
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
        scope.launch {
            snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
                if (mutableFollowsLatest) {
                    if (canScrollForward) listState.requestScrollToStart()
                } else if (!canScrollForward) {
                    mutableFollowsLatest = true
                }
            }
        }
    }

    private fun registerOlderDemand(
        window: HistoryItemWindowImpl,
        index: Int,
    ) {
        if (
            mutableHistoryItems.value === window &&
            index >= (window.size - OlderDemandDistance).coerceAtLeast(0) &&
            (mutableLoadState.value as? AgentHistoryLoadState.Ready)?.hasOlder == true &&
            olderDemandPending.compareAndSet(expect = false, update = true)
        ) {
            if (commands.trySend(HistoryCommand.LoadOlder).isFailure) {
                olderDemandPending.value = false
            }
        }
    }

    override suspend fun read(item: HistoryItemViewModel): StableCleanEvent {
        val index = item.storageIndex
        return historyReadSemaphore.withPermit {
            check(mutableHistoryItems.value.find(index) === item) {
                "History item $index is no longer current."
            }
            withContext(Dispatchers.Default) {
                agentState.storage.stable[index]
            }
        }
    }

    override suspend fun elapsedSincePrevious(item: HistoryItemViewModel): Duration? =
        historyReadSemaphore.withPermit {
            check(item !is HistoryItemViewModel.TurnTimeMarker) {
                "A turn time marker does not have an item elapsed duration."
            }
            val oldestIndex = item.oldestStoredIndex
            val newestIndex = item.newestStoredIndex
            check(mutableHistoryItems.value.contains(item)) {
                "History item $oldestIndex..$newestIndex is no longer current."
            }
            withContext(Dispatchers.Default) {
                val previousIndex = agentState.storage.stable.prevIndex(oldestIndex)
                    ?: return@withContext null
                val timestamp = agentState.storage.timestamp
                if (
                    timestamp.floorToIndex(previousIndex) != previousIndex ||
                    timestamp.floorToIndex(newestIndex) != newestIndex
                ) {
                    return@withContext null
                }
                (timestamp[newestIndex] - timestamp[previousIndex])
                    .takeIf { elapsed -> elapsed >= Duration.ZERO && elapsed.isFinite() }
            }
        }

    override fun contains(generation: Long, storageIndex: Int): Boolean =
        mutableHistoryItems.value.let { window ->
            generation == window.generation && window.find(storageIndex) != null
        }

    override fun notifyContentChanged() {
        if (mutableFollowsLatest) listState.requestScrollToStart()
    }

    override fun requestScrollToLatest() {
        mutableFollowsLatest = true
        listState.requestScrollToStart()
    }

    override fun close() {
        commands.close()
        scope.cancel()
    }

    private suspend fun runHistoryLoop() {
        var initialized = false
        var observedLatestIndex = -1
        var nextOlderIndex: Int? = null
        var lastInvalidation: Pair<Int, Int>? = null
        var newestOpenItems: List<HistoryItemViewModel> = emptyList()
        var newestTimeMarkers: List<HistoryItemViewModel.TurnTimeMarker> = emptyList()
        var sealedSequence: HistorySequence = HistorySequence.Empty
        var activeTurn = runningTurn.value != null

        fun publishCurrent(generation: Long = mutableHistoryItems.value.generation) {
            var sequence = HistorySequence.concat(
                HistorySequence.of(newestOpenItems),
                sealedSequence,
            )
            newestTimeMarkers.forEach { marker ->
                sequence = sequence.insert(marker)
            }
            mutableLatestTurnTimeMarker.value?.let { marker ->
                sequence = sequence.insert(marker)
            }
            publishHistoryItems(
                sequence = sequence,
                generation = generation,
            )
        }

        suspend fun updateLatestTurnState() {
            val latestIndex = observedLatestIndex
            val timeMarker = if (latestIndex >= 0 && !activeTurn) {
                timelineCursor.latestTurnTimeMarker(latestIndex)
            } else {
                null
            }
            val activeDuration = if (latestIndex >= 0 && activeTurn) {
                timelineCursor.activeTurnDuration(latestIndex)
            } else {
                null
            }
            val previousTimeMarker = mutableLatestTurnTimeMarker.value
            val previousActiveDuration = mutableActiveTurnDuration.value
            if (previousTimeMarker == timeMarker && previousActiveDuration == activeDuration) {
                return
            }
            mutableLatestTurnTimeMarker.value = timeMarker
            mutableActiveTurnDuration.value = activeDuration
            if (previousTimeMarker != timeMarker) {
                publishCurrent()
            }
        }

        suspend fun reload(latestIndex: Int, invalidate: Boolean) {
            val currentGeneration = mutableHistoryItems.value.generation
            val reloadGeneration = if (invalidate) {
                check(currentGeneration < Long.MAX_VALUE) { "History generations are exhausted." }
                currentGeneration + 1
            } else {
                currentGeneration
            }
            if (invalidate) timelineCursor.reset()
            timelineCursor.refreshThrough(latestIndex)
            newestTimeMarkers = emptyList()
            newestOpenItems = emptyList()
            sealedSequence = HistorySequence.Empty
            publishHistoryItems(
                sequence = HistorySequence.Empty,
                generation = reloadGeneration,
            )
            mutableLoadState.value = AgentHistoryLoadState.Initializing
            val batch = loadBatch(latestIndex, timelineCursor)
            val projection = projectNewestHistory(batch.items, timelineCursor.endIndexes())
            observedLatestIndex = latestIndex
            nextOlderIndex = batch.nextOlderIndex
            initialized = true
            newestOpenItems = projection.openItems
            sealedSequence = HistorySequence.of(
                timelineCursor.mergeTimeline(projection.sealedItems).map { entry -> entry.item },
            )
            publishCurrent(reloadGeneration)
            updateLatestTurnState()
            mutableLoadState.value = AgentHistoryLoadState.Ready(
                hasOlder = nextOlderIndex != null,
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

            val addedBoundaries = timelineCursor.refreshThrough(latestIndex)
            val current = mutableHistoryItems.value.sequence
            val newestLoaded = current.takeUnless { it === HistorySequence.Empty }?.newestStableIndex
            val newestStored = withContext(Dispatchers.Default) {
                agentState.storage.stable.floorToIndex(latestIndex)
            }
            observedLatestIndex = latestIndex
            if (newestStored == null || newestStored == newestLoaded) {
                if (addedBoundaries.isNotEmpty()) {
                    newestTimeMarkers = (
                        timelineCursor.timeMarkersFor(addedBoundaries) + newestTimeMarkers
                        ).distinctBy { marker -> marker.markerIndex to marker.endIndex }
                }
                val previousTimeMarker = mutableLatestTurnTimeMarker.value
                updateLatestTurnState()
                if (
                    addedBoundaries.isNotEmpty() &&
                    previousTimeMarker == mutableLatestTurnTimeMarker.value
                ) {
                    publishCurrent()
                }
                return
            }
            if (newestLoaded == null) {
                reload(latestIndex, invalidate = false)
                return
            }

            val additions = loadNewerThan(
                fromInclusive = newestStored,
                exclusiveBoundary = newestLoaded,
            )
            if (additions.isNotEmpty()) {
                val projectionInput = ArrayList<HistoryItemViewModel>(
                    additions.size + newestOpenItems.size,
                )
                projectionInput += additions
                projectionInput += newestOpenItems
                val projection = projectNewestHistory(projectionInput, timelineCursor.endIndexes())
                newestOpenItems = projection.openItems
                val projectedSealed = timelineCursor.mergeTimeline(projection.sealedItems)
                    .map { entry -> entry.item }
                val mergedSealed = (
                    newestTimeMarkers + projectedSealed
                    )
                    .distinctBy { item ->
                        when (item) {
                            is HistoryItemViewModel.TurnTimeMarker ->
                                item.markerIndex to item.endIndex

                            else -> item
                        }
                    }
                    .sortedByDescending { item -> item.newestOrderKey }
                newestTimeMarkers = emptyList()
                sealedSequence = HistorySequence.concat(
                    HistorySequence.of(mergedSealed),
                    sealedSequence,
                )
                publishCurrent()
            }
            updateLatestTurnState()
        }

        suspend fun loadOlder() {
            try {
                val firstIndex = nextOlderIndex
                if (firstIndex == null) {
                    mutableLoadState.value = AgentHistoryLoadState.Ready(hasOlder = false)
                    return
                }
                mutableLoadState.value = AgentHistoryLoadState.LoadingOlder
                val batch = loadBatch(firstIndex, timelineCursor)
                val projectedItems = timelineCursor.mergeTimeline(
                    projectSealedHistory(batch.items, timelineCursor.endIndexes()),
                ).map { entry -> entry.item }
                nextOlderIndex = batch.nextOlderIndex
                sealedSequence = HistorySequence.concat(
                    sealedSequence,
                    HistorySequence.of(projectedItems),
                )
                publishCurrent()
                mutableLoadState.value = AgentHistoryLoadState.Ready(
                        hasOlder = nextOlderIndex != null,
                )
            } finally {
                olderDemandPending.value = false
            }
        }

        for (command in commands) {
            try {
                when (command) {
                    is HistoryCommand.Refresh -> refresh(command.latestIndex)
                    HistoryCommand.LoadOlder -> loadOlder()
                    is HistoryCommand.UpdateLatestTurn -> {
                        activeTurn = command.active
                        updateLatestTurnState()
                    }
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
                olderDemandPending.value = false
                mutableLoadState.value = AgentHistoryLoadState.Failed(
                    failure.message ?: failure.toString(),
                )
            }
        }
    }

    private suspend fun loadBatch(
        fromInclusive: Int,
        timelineCursor: HistoryTimelineCursor,
    ): LoadedHistoryBatch =
        withContext(Dispatchers.Default) {
            if (fromInclusive < 0) return@withContext LoadedHistoryBatch(emptyList(), null)
            val items = ArrayList<HistoryItemViewModel>(MaximumHistoryBatchSize)
            var index = agentState.storage.stable.floorToIndex(fromInclusive)
            var projectedItemCount = 0
            while (index != null && projectedItemCount < HistoryBatchSize) {
                items += agentState.storage.stable[index].toHistoryItem(index)
                projectedItemCount += 1
                if (timelineCursor.hasTimeMarkerAtEndIndex(index)) {
                    projectedItemCount += 1
                }
                index = agentState.storage.stable.prevIndex(index)
            }
            while (
                index != null &&
                projectedItemCount < MaximumHistoryBatchSize &&
                items.last().isAutomaticallyFoldable
            ) {
                items += agentState.storage.stable[index].toHistoryItem(index)
                projectedItemCount += 1
                if (timelineCursor.hasTimeMarkerAtEndIndex(index)) {
                    projectedItemCount += 1
                }
                index = agentState.storage.stable.prevIndex(index)
            }
            LoadedHistoryBatch(items, index)
        }

    private suspend fun loadNewerThan(
        fromInclusive: Int,
        exclusiveBoundary: Int,
    ): List<HistoryItemViewModel> = withContext(Dispatchers.Default) {
        val items = mutableListOf<HistoryItemViewModel>()
        var index: Int? = fromInclusive
        while (index != null && index > exclusiveBoundary) {
            items += agentState.storage.stable[index].toHistoryItem(index)
            index = agentState.storage.stable.prevIndex(index)
        }
        items
    }

    private suspend fun loadPendingTools(latestIndex: Int): List<UnstableCleanEvent> =
        withContext(Dispatchers.Default) {
            if (
                latestIndex >= 0 &&
                agentState.storage.unstable.floorToIndex(latestIndex) != null
            ) {
                agentState.storage.unstable[latestIndex]
            } else {
                emptyList()
            }
        }

    private fun publishHistoryItems(
        sequence: HistorySequence,
        generation: Long = mutableHistoryItems.value.generation,
    ) {
        val current = mutableHistoryItems.value
        if (current.generation == generation && current.sequence === sequence) return
        mutableHistoryItems.value = HistoryItemWindowImpl(
            generation = generation,
            sequence = sequence,
            onOlderDemand = ::registerOlderDemand,
        )
        notifyContentChanged()
    }

    private fun publishPendingTools(pending: List<UnstableCleanEvent>) {
        if (mutablePendingTools.value == pending) return
        mutablePendingTools.value = pending
        notifyContentChanged()
    }

    private fun publishStreamingItem(item: HistoryStreamingItem?) {
        if (mutableStreamingItem.value == item) return
        mutableStreamingItem.value = item
        notifyContentChanged()
    }

    private fun onScrollInteraction(interaction: ScrollInteraction) {
        if (
            interaction.orientation != ScrollOrientation.Vertical ||
            interaction.consumedDelta == 0
        ) {
            return
        }
        when (interaction.source) {
            ScrollInputSource.Pointer,
            ScrollInputSource.Keyboard,
                -> if (interaction.consumedDelta < 0) {
                mutableFollowsLatest = false
            } else if (!listState.canScrollForward) {
                mutableFollowsLatest = true
            }

            ScrollInputSource.FocusRelocation,
            ScrollInputSource.Programmatic,
                -> Unit
        }
    }
}

/** Creates the History View state owned by one materialized Agent ViewModel. */
public fun createAgentHistoryViewModel(
    agentState: KodexAgentState,
    ownerScope: CoroutineScope,
    runningTurn: StateFlow<Job?> = MutableStateFlow(null),
): AgentHistoryViewModel = AgentHistoryViewModelImpl(
    agentState = agentState,
    scope = ownerScope,
    runningTurn = runningTurn,
)

/** Koin-resolved History creator with one exact Agent runtime parameter set. */
@Factory
public class DefaultAgentHistoryViewModelFactory(
    @InjectedParam private val agentState: KodexAgentState,
    @InjectedParam private val ownerScope: CoroutineScope,
    @InjectedParam private val runningTurn: StateFlow<Job?>,
) {
    public fun create(): AgentHistoryViewModel =
        createAgentHistoryViewModel(agentState, ownerScope, runningTurn)
}

private fun StableCleanEvent.toHistoryItem(index: Int): HistoryItemViewModel = when (this) {
    is StableCleanEvent.UserMessage,
    is StableCleanEvent.AssistantMessage,
    is StableCleanEvent.DeveloperMessage,
    is StableCleanEvent.AgentMessage,
        -> HistoryItemViewModel.Message(index)

    is StableCleanEvent.Reasoning -> HistoryItemViewModel.Reasoning(index)
    StableCleanEvent.ContextCompaction -> HistoryItemViewModel.ContextCompaction(index)
    is StableRequestUserInputToolEvent -> HistoryItemViewModel.RequestUserInput(index)
    is StablePatchToolEvent -> HistoryItemViewModel.Patch(index)
    is StablePlanUpdate -> HistoryItemViewModel.PlanUpdate(index)
    is StableCleanEvent.CompletedTool -> HistoryItemViewModel.Tool(index)
}

internal data class NewestHistoryProjection(
    val openItems: List<HistoryItemViewModel>,
    val sealedItems: List<HistoryItemViewModel>,
)

/**
 * Projects only the newest changed segment. Its leading foldable run remains individually visible
 * until a later breaker seals it.
 */
internal fun projectNewestHistory(
    items: List<HistoryItemViewModel>,
    turnEndIndexes: Set<Int> = emptySet(),
): NewestHistoryProjection {
    var openItemCount = 0
    while (
        openItemCount < items.size &&
        items[openItemCount].isAutomaticallyFoldable &&
        (
            openItemCount == 0 ||
                items[openItemCount].storageIndex !in turnEndIndexes
            )
    ) {
        openItemCount += 1
    }
    return NewestHistoryProjection(
        openItems = items.subList(0, openItemCount).toList(),
        sealedItems = projectSealedHistory(
            items = items.subList(openItemCount, items.size),
            turnEndIndexes = turnEndIndexes,
        ),
    )
}

/** Projects complete or forcibly bounded work runs without retaining decoded stable events. */
internal fun projectSealedHistory(
    items: List<HistoryItemViewModel>,
    turnEndIndexes: Set<Int> = emptySet(),
): List<HistoryItemViewModel> {
    if (items.isEmpty()) return emptyList()
    val projected = ArrayList<HistoryItemViewModel>(items.size)
    var position = 0
    while (position < items.size) {
        val first = items[position]
        if (!first.isAutomaticallyFoldable) {
            check(first !is HistoryItemViewModel.WorkGroup) {
                "A projected work group cannot be projected again."
            }
            projected += first
            position += 1
            continue
        }

        val start = position
        position += 1
        while (
            position < items.size &&
            items[position].isAutomaticallyFoldable &&
            items[position].storageIndex !in turnEndIndexes
        ) {
            position += 1
        }
        if (position - start == 1) {
            projected += first
        } else {
            projected += HistoryItemViewModel.WorkGroup(items.subList(start, position))
        }
    }
    return projected
}

private fun KodexAgentStateValue.toStreamingItem(): HistoryStreamingItem? = when (this) {
    KodexAgentStateValue.RequestResponse.Started -> HistoryStreamingItem.Started
    is KodexAgentStateValue.RequestResponse.Message ->
        HistoryStreamingItem.Output(HistoryStreamingKind.Message, events)

    is KodexAgentStateValue.RequestResponse.AgentMessage ->
        HistoryStreamingItem.Output(HistoryStreamingKind.AgentMessage, events)

    is KodexAgentStateValue.RequestResponse.Reasoning ->
        HistoryStreamingItem.Output(HistoryStreamingKind.Reasoning, events)

    is KodexAgentStateValue.RequestResponse.ToolCall ->
        HistoryStreamingItem.Output(HistoryStreamingKind.ToolCall, events)

    is KodexAgentStateValue.RequestResponse.Unknown ->
        HistoryStreamingItem.Output(HistoryStreamingKind.Unknown, events)

    KodexAgentStateValue.Compacting -> HistoryStreamingItem.Compacting
    else -> null
}

private val HistoryItemViewModel.storageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.Message -> index
        is HistoryItemViewModel.Reasoning -> index
        is HistoryItemViewModel.Tool -> index
        is HistoryItemViewModel.RequestUserInput -> index
        is HistoryItemViewModel.Patch -> index
        is HistoryItemViewModel.PlanUpdate -> index
        is HistoryItemViewModel.ContextCompaction -> index
        is HistoryItemViewModel.TurnTimeMarker ->
            error("A turn time marker cannot be read as a stored history event.")
        is HistoryItemViewModel.WorkGroup ->
            error("A folded history work group cannot be read as one stable event.")
    }

private val HistoryItemViewModel.isAutomaticallyFoldable: Boolean
    get() = when (this) {
        is HistoryItemViewModel.Reasoning,
        is HistoryItemViewModel.Tool,
        is HistoryItemViewModel.Patch,
            -> true

        is HistoryItemViewModel.Message,
        is HistoryItemViewModel.RequestUserInput,
        is HistoryItemViewModel.PlanUpdate,
        is HistoryItemViewModel.ContextCompaction,
        is HistoryItemViewModel.TurnTimeMarker,
        is HistoryItemViewModel.WorkGroup,
            -> false
    }

private val HistoryItemViewModel.newestStoredIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> indexRange.last
        is HistoryItemViewModel.TurnTimeMarker -> endIndex
        else -> storageIndex
    }

private val HistoryItemViewModel.oldestStoredIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> indexRange.first
        is HistoryItemViewModel.TurnTimeMarker -> endIndex
        else -> storageIndex
    }

private fun stableOrderKey(index: Int): Long = index.toLong() * 2L + 1L

// History is newest-first while the UI uses reverseLayout. A time marker belongs immediately
// after its ending stable item in chronological order, so it sorts just before that item here.
private fun timeMarkerOrderKey(endIndex: Int): Long = endIndex.toLong() * 2L + 2L

private val HistoryItemViewModel.newestOrderKey: Long
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> stableOrderKey(indexRange.last)
        is HistoryItemViewModel.TurnTimeMarker -> timeMarkerOrderKey(endIndex)
        else -> stableOrderKey(storageIndex)
    }

private val HistoryItemViewModel.oldestOrderKey: Long
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> stableOrderKey(indexRange.first)
        is HistoryItemViewModel.TurnTimeMarker -> timeMarkerOrderKey(endIndex)
        else -> stableOrderKey(storageIndex)
    }

private fun HistoryItemViewModel.find(storageIndex: Int): HistoryItemViewModel? {
    return when (this) {
        is HistoryItemViewModel.WorkGroup -> {
            if (storageIndex !in indexRange) return null
            for (position in 0 until itemCount) {
                val child = childAt(position)
                if (child.storageIndex == storageIndex) return child
            }
            null
        }

        is HistoryItemViewModel.TurnTimeMarker -> null
        else -> takeIf { this.storageIndex == storageIndex }
    }
}

private data class LoadedHistoryBatch(
    val items: List<HistoryItemViewModel>,
    val nextOlderIndex: Int?,
)

private data class TurnMarker(
    val index: Int,
    val turnId: String,
)

private data class TurnBoundary(
    val marker: TurnMarker,
    val nextMarker: TurnMarker,
    val endIndex: Int?,
)

private sealed interface HistoryTimelineEntry {
    val item: HistoryItemViewModel
    val sourceOrderKey: Long

    data class Stable(
        val sourceIndex: Int,
        override val item: HistoryItemViewModel,
    ) : HistoryTimelineEntry {
        override val sourceOrderKey: Long = sourceIndex.toLong() * 2L + 1L
    }

    data class TurnTimeMarker(
        val sourceIndex: Int,
        override val item: HistoryItemViewModel.TurnTimeMarker,
    ) : HistoryTimelineEntry {
        override val sourceOrderKey: Long = sourceIndex.toLong() * 2L
    }
}

/**
 * Incremental cursor over the sparse stable and turn-marker timelines.
 *
 * It never decodes stable payloads while advancing marker metadata. The merge only emits a
 * time-marker entry when its ending stable item is materialized, so paging cannot expose a
 * dangling duration row.
 */
private class HistoryTimelineCursor(
    private val agentState: KodexAgentState,
) {
    private val markers = mutableListOf<TurnMarker>()
    private val boundaries = mutableListOf<TurnBoundary>()
    private val boundariesByEndIndex = mutableMapOf<Int, MutableList<TurnBoundary>>()
    private val turnEndIndexes = mutableSetOf<Int>()
    private val timeMarkerCache =
        mutableMapOf<Pair<Int, Int>, HistoryItemViewModel.TurnTimeMarker?>()
    private var scannedSettingsIndex: Int? = null
    private var scannedThrough: Int = -1
    private var previousTurnId: String? = null

    suspend fun refreshThrough(latestIndex: Int): List<TurnBoundary> = withContext(Dispatchers.Default) {
        if (latestIndex < scannedThrough) reset()
        var index = if (scannedSettingsIndex == null) {
            agentState.storage.settings.ceilToIndex(0)
        } else {
            agentState.storage.settings.nextIndex(scannedSettingsIndex!!)
        }
        val addedBoundaries = mutableListOf<TurnBoundary>()
        while (index != null && index <= latestIndex) {
            val settings = agentState.storage.settings[index]
            if (previousTurnId == null || settings.turnId != previousTurnId) {
                val marker = TurnMarker(index = index, turnId = settings.turnId)
                markers.lastOrNull()?.let { previous ->
                    val boundary = TurnBoundary(
                        marker = previous,
                        nextMarker = marker,
                        endIndex = agentState.storage.stable.prevIndex(marker.index),
                    )
                    boundaries += boundary
                    boundary.endIndex?.let { endIndex ->
                        boundariesByEndIndex.getOrPut(endIndex) { mutableListOf() } += boundary
                        turnEndIndexes += endIndex
                    }
                    addedBoundaries += boundary
                }
                markers += marker
            }
            previousTurnId = settings.turnId
            scannedSettingsIndex = index
            index = agentState.storage.settings.nextIndex(index)
        }
        scannedThrough = latestIndex
        addedBoundaries
    }

    fun reset() {
        markers.clear()
        boundaries.clear()
        boundariesByEndIndex.clear()
        turnEndIndexes.clear()
        timeMarkerCache.clear()
        scannedSettingsIndex = null
        scannedThrough = -1
        previousTurnId = null
    }

    fun endIndexes(): Set<Int> = turnEndIndexes

    suspend fun hasTimeMarkerAtEndIndex(index: Int): Boolean =
        boundariesByEndIndex[index]?.any { boundary -> timeMarkerFor(boundary) != null } == true

    suspend fun mergeTimeline(items: List<HistoryItemViewModel>): List<HistoryTimelineEntry> {
        if (items.isEmpty() || boundariesByEndIndex.isEmpty()) {
            return items.map { item ->
                HistoryTimelineEntry.Stable(
                    sourceIndex = item.newestStoredIndex,
                    item = item,
                )
            }
        }
        val result = ArrayList<HistoryTimelineEntry>(items.size)
        for (item in items) {
            boundariesByEndIndex[item.newestStoredIndex]?.forEach { boundary ->
                timeMarkerFor(boundary)?.let { marker ->
                    result += HistoryTimelineEntry.TurnTimeMarker(
                        sourceIndex = boundary.nextMarker.index,
                        item = marker,
                    )
                }
            }
            result += HistoryTimelineEntry.Stable(
                sourceIndex = item.newestStoredIndex,
                item = item,
            )
        }
        require(result.zipWithNext().all { (newer, older) ->
            newer.sourceOrderKey > older.sourceOrderKey
        }) {
            "Merged history timeline entries must be newest-first."
        }
        return result
    }

    suspend fun timeMarkersFor(
        boundaries: List<TurnBoundary>,
    ): List<HistoryItemViewModel.TurnTimeMarker> =
        boundaries.mapNotNull { boundary -> timeMarkerFor(boundary) }

    suspend fun latestTurnTimeMarker(
        latestIndex: Int,
    ): HistoryItemViewModel.TurnTimeMarker? = withContext(Dispatchers.Default) {
        val marker = markers.lastOrNull() ?: return@withContext null
        val endIndex = agentState.storage.stable.floorToIndex(latestIndex)
            ?.takeIf { it > marker.index }
            ?: return@withContext null
        val cacheKey = marker.index to endIndex
        if (cacheKey in timeMarkerCache) {
            return@withContext timeMarkerCache[cacheKey]
        }
        val startTimestamp = startTimestamp(marker.index, endIndex)
            ?: return@withContext null
        val duration = exactTimestamp(endIndex)?.let { timestamp -> timestamp - startTimestamp }
            ?: return@withContext null
        val result = duration.takeIf { it >= Duration.ZERO && it.isFinite() }?.let {
            HistoryItemViewModel.TurnTimeMarker(
                markerIndex = marker.index,
                endIndex = endIndex,
                duration = it,
            )
        }
        timeMarkerCache[cacheKey] = result
        result
    }

    suspend fun activeTurnDuration(latestIndex: Int): Duration? = withContext(Dispatchers.Default) {
        val marker = markers.lastOrNull() ?: return@withContext null
        val latestStableIndex = agentState.storage.stable.floorToIndex(latestIndex)
        val startTimestamp = startTimestamp(marker.index, latestStableIndex)
            ?: return@withContext null
        (Clock.System.now() - startTimestamp)
            .takeIf { it >= Duration.ZERO && it.isFinite() }
    }

    private suspend fun timeMarkerFor(boundary: TurnBoundary): HistoryItemViewModel.TurnTimeMarker? {
        val endIndex = boundary.endIndex ?: return null
        if (endIndex <= boundary.marker.index) return null
        val cacheKey = boundary.marker.index to endIndex
        if (cacheKey in timeMarkerCache) return timeMarkerCache[cacheKey]
        val marker = withContext(Dispatchers.Default) {
            val startTimestamp = startTimestamp(boundary.marker.index, endIndex)
                ?: return@withContext null
            val endTimestamp = exactTimestamp(endIndex) ?: return@withContext null
            val duration = (endTimestamp - startTimestamp)
                .takeIf { it >= Duration.ZERO && it.isFinite() }
                ?: return@withContext null
            HistoryItemViewModel.TurnTimeMarker(
                markerIndex = boundary.marker.index,
                endIndex = endIndex,
                duration = duration,
            )
        }
        timeMarkerCache[cacheKey] = marker
        return marker
    }

    private suspend fun startTimestamp(markerIndex: Int, endIndex: Int?): Instant? {
        exactTimestamp(markerIndex)?.let { return it }
        if (markerIndex != 0) return null
        val firstStableIndex = agentState.storage.stable.ceilToIndex(markerIndex + 1)
            ?.takeIf { endIndex == null || it <= endIndex }
            ?: return null
        return exactTimestamp(firstStableIndex)
    }

    private suspend fun exactTimestamp(index: Int): Instant? {
        val timestamp = agentState.storage.timestamp
        if (timestamp.floorToIndex(index) != index) return null
        return timestamp[index]
    }
}

private sealed interface HistoryCommand {
    data class Refresh(val latestIndex: Int) : HistoryCommand
    data object LoadOlder : HistoryCommand
    data class UpdateLatestTurn(val active: Boolean) : HistoryCommand
    data class ExternalWriteFinished(val startIndex: Int, val endIndex: Int) : HistoryCommand
}

private class HistoryItemWindowImpl(
    override val generation: Long,
    val sequence: HistorySequence,
    private val onOlderDemand: (HistoryItemWindowImpl, Int) -> Unit,
) : HistoryItemWindow {
    override val size: Int = sequence.size

    override fun peek(index: Int): HistoryItemViewModel = sequence[index]

    override fun get(index: Int): HistoryItemViewModel {
        val item = sequence[index]
        onOlderDemand(this, index)
        return item
    }

    fun find(storageIndex: Int): HistoryItemViewModel? = sequence.find(storageIndex)

    fun contains(item: HistoryItemViewModel): Boolean = when (item) {
        is HistoryItemViewModel.TurnTimeMarker -> false
        is HistoryItemViewModel.WorkGroup ->
            find(item.indexRange.first) === item.childAt(item.itemCount - 1) &&
                find(item.indexRange.last) === item.childAt(0)

        else -> find(item.storageIndex) === item
    }
}

/**
 * Immutable balanced rope. Publishing a batch costs `O(log n)` structural nodes and indexed reads
 * cost `O(log n)` while retaining exact child instances.
 */
private data class HistorySequenceEntry(
    val item: HistoryItemViewModel,
    val newestOrderKey: Long,
    val oldestOrderKey: Long,
    val newestStableIndex: Int?,
    val oldestStableIndex: Int?,
) {
    fun containsStableIndex(index: Int): Boolean {
        val oldest = oldestStableIndex ?: return false
        val newest = newestStableIndex ?: return false
        return index in oldest..newest
    }

    companion object {
        fun of(item: HistoryItemViewModel): HistorySequenceEntry =
            when (item) {
                is HistoryItemViewModel.WorkGroup -> HistorySequenceEntry(
                    item = item,
                    newestOrderKey = stableOrderKey(item.indexRange.last),
                    oldestOrderKey = stableOrderKey(item.indexRange.first),
                    newestStableIndex = item.indexRange.last,
                    oldestStableIndex = item.indexRange.first,
                )

                is HistoryItemViewModel.TurnTimeMarker -> HistorySequenceEntry(
                    item = item,
                    newestOrderKey = timeMarkerOrderKey(item.endIndex),
                    oldestOrderKey = timeMarkerOrderKey(item.endIndex),
                    newestStableIndex = null,
                    oldestStableIndex = null,
                )

                else -> HistorySequenceEntry(
                    item = item,
                    newestOrderKey = stableOrderKey(item.storageIndex),
                    oldestOrderKey = stableOrderKey(item.storageIndex),
                    newestStableIndex = item.storageIndex,
                    oldestStableIndex = item.storageIndex,
                )
            }
    }
}

private sealed interface HistorySequence {
    val size: Int
    val height: Int
    val newestOrderKey: Long
    val oldestOrderKey: Long
    val newestStableIndex: Int?
    val oldestStableIndex: Int?

    operator fun get(index: Int): HistoryItemViewModel

    fun find(storageIndex: Int): HistoryItemViewModel?

    fun containsStableIndex(index: Int): Boolean

    fun insert(item: HistoryItemViewModel): HistorySequence

    data object Empty : HistorySequence {
        override val size: Int = 0
        override val height: Int = 0
        override val newestOrderKey: Long
            get() = error("An empty history sequence has no newest index.")
        override val oldestOrderKey: Long
            get() = error("An empty history sequence has no oldest index.")
        override val newestStableIndex: Int? = null
        override val oldestStableIndex: Int? = null

        override fun get(index: Int): HistoryItemViewModel =
            throw IndexOutOfBoundsException("History item index $index is out of bounds for 0 items.")

        override fun find(storageIndex: Int): HistoryItemViewModel? = null

        override fun containsStableIndex(index: Int): Boolean = false

        override fun insert(item: HistoryItemViewModel): HistorySequence = of(listOf(item))
    }

    class Leaf(
        val entries: List<HistorySequenceEntry>,
    ) : HistorySequence {
        init {
            require(entries.isNotEmpty()) { "A history sequence leaf must not be empty." }
            require(entries.size <= HistoryLeafSize) {
                "A history sequence leaf cannot exceed $HistoryLeafSize items."
            }
            require(entries.zipWithNext().all { (newer, older) ->
                newer.oldestOrderKey > older.newestOrderKey
            }) {
                "History sequence items must be strictly newest-first."
            }
        }

        override val size: Int = entries.size
        override val height: Int = 1
        override val newestOrderKey: Long = entries.first().newestOrderKey
        override val oldestOrderKey: Long = entries.last().oldestOrderKey
        override val newestStableIndex: Int? =
            entries.firstNotNullOfOrNull { entry -> entry.newestStableIndex }
        override val oldestStableIndex: Int? =
            entries.asReversed().firstNotNullOfOrNull { entry -> entry.oldestStableIndex }

        override fun get(index: Int): HistoryItemViewModel = entries[index].item

        override fun find(storageIndex: Int): HistoryItemViewModel? {
            if (!containsStableIndex(storageIndex)) return null
            for (entry in entries) {
                if (entry.containsStableIndex(storageIndex)) {
                    return entry.item.find(storageIndex)
                }
            }
            return null
        }

        override fun containsStableIndex(index: Int): Boolean {
            val oldest = oldestStableIndex ?: return false
            val newest = newestStableIndex ?: return false
            return index in oldest..newest
        }

        override fun insert(item: HistoryItemViewModel): HistorySequence {
            val entry = HistorySequenceEntry.of(item)
            if (entries.any { existing -> existing.newestOrderKey == entry.newestOrderKey }) {
                return this
            }
            require(entries.all { existing ->
                entry.oldestOrderKey > existing.newestOrderKey ||
                    existing.oldestOrderKey > entry.newestOrderKey
            }) {
                "A history item overlaps an existing sequence item."
            }
            return of(
                (entries + entry)
                    .sortedByDescending { existing -> existing.newestOrderKey }
                    .map { existing -> existing.item },
            )
        }
    }

    class Branch(
        val left: HistorySequence,
        val right: HistorySequence,
    ) : HistorySequence {
        init {
            require(left !== Empty && right !== Empty) {
                "A history sequence branch must have two non-empty children."
            }
            require(left.oldestOrderKey > right.newestOrderKey) {
                "History sequence branches must be strictly newest-first."
            }
        }

        override val size: Int = checkedHistorySize(left.size, right.size)
        override val height: Int = maxOf(left.height, right.height) + 1
        override val newestOrderKey: Long = left.newestOrderKey
        override val oldestOrderKey: Long = right.oldestOrderKey
        override val newestStableIndex: Int? = left.newestStableIndex ?: right.newestStableIndex
        override val oldestStableIndex: Int? = right.oldestStableIndex ?: left.oldestStableIndex

        override fun get(index: Int): HistoryItemViewModel {
            if (index !in 0 until size) {
                throw IndexOutOfBoundsException(
                    "History item index $index is out of bounds for $size items.",
                )
            }
            return if (index < left.size) left[index] else right[index - left.size]
        }

        override fun find(storageIndex: Int): HistoryItemViewModel? {
            if (!containsStableIndex(storageIndex)) return null
            return when {
                left.containsStableIndex(storageIndex) -> left.find(storageIndex)
                right.containsStableIndex(storageIndex) -> right.find(storageIndex)
                else -> null
            }
        }

        override fun containsStableIndex(index: Int): Boolean {
            val oldest = oldestStableIndex ?: return false
            val newest = newestStableIndex ?: return false
            return index in oldest..newest
        }

        override fun insert(item: HistoryItemViewModel): HistorySequence {
            val entry = HistorySequenceEntry.of(item)
            return when {
                entry.newestOrderKey > newestOrderKey -> concat(of(listOf(item)), this)
                entry.oldestOrderKey < oldestOrderKey -> concat(this, of(listOf(item)))
                entry.newestOrderKey < left.oldestOrderKey -> {
                    concat(left, right.insert(item))
                }

                else -> {
                    concat(left.insert(item), right)
                }
            }
        }
    }

    companion object {
        fun of(items: List<HistoryItemViewModel>): HistorySequence {
            var result: HistorySequence = Empty
            items.map(HistorySequenceEntry.Companion::of)
                .chunked(HistoryLeafSize)
                .forEach { chunk ->
                    result = concat(result, Leaf(chunk))
            }
            return result
        }

        fun concat(left: HistorySequence, right: HistorySequence): HistorySequence {
            if (left === Empty) return right
            if (right === Empty) return left
            return when {
                left.height > right.height + 1 -> {
                    check(left is Branch)
                    balance(left.left, concat(left.right, right))
                }

                right.height > left.height + 1 -> {
                    check(right is Branch)
                    balance(concat(left, right.left), right.right)
                }

                else -> Branch(left, right)
            }
        }

        private fun balance(left: HistorySequence, right: HistorySequence): HistorySequence =
            when {
                left.height > right.height + 1 -> {
                    check(left is Branch)
                    if (left.left.height >= left.right.height) {
                        Branch(left.left, Branch(left.right, right))
                    } else {
                        val middle = left.right
                        check(middle is Branch)
                        Branch(
                            Branch(left.left, middle.left),
                            Branch(middle.right, right),
                        )
                    }
                }

                right.height > left.height + 1 -> {
                    check(right is Branch)
                    if (right.right.height >= right.left.height) {
                        Branch(Branch(left, right.left), right.right)
                    } else {
                        val middle = right.left
                        check(middle is Branch)
                        Branch(
                            Branch(left, middle.left),
                            Branch(middle.right, right.right),
                        )
                    }
                }

                else -> Branch(left, right)
            }
    }
}

private const val HistoryBatchSize: Int = 64
private const val MaximumHistoryBatchSize: Int = HistoryBatchSize * 2
private const val HistoryLeafSize: Int = 64
private const val OlderDemandDistance: Int = 8
private const val HistoryReadParallelism: Int = 8

private fun checkedHistorySize(left: Int, right: Int): Int {
    val size = left.toLong() + right
    require(size <= Int.MAX_VALUE) { "History item count exceeds Int.MAX_VALUE." }
    return size.toInt()
}
