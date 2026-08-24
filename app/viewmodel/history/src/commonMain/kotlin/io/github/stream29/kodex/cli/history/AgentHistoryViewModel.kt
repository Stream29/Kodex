package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.nextIndex
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.history.contract.item.ContextCompactionHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.TurnTimeMarkerHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupChildHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.openai.ResponsesStreamEvent
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
    private val olderDemandPending = MutableStateFlow(false)
    private val timelineCursor = HistoryTimelineCursor(agentState)
    private var activeGeneration: Long = 0
    private var closed = false

    private val itemCache = mutableMapOf<Int, HistoryItemViewModel>()
    private val groupCache = mutableMapOf<GroupKey, WorkGroupHistoryItemViewModelImpl>()

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
        MutableStateFlow<TurnTimeMarkerHistoryItemViewModel?>(null)
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

    override fun contains(generation: Long, storageIndex: Int): Boolean {
        val window = mutableHistoryItems.value
        return generation == window.generation && window.sequence.containsStableIndex(storageIndex)
    }

    override fun notifyContentChanged() {
        if (mutableFollowsLatest) listState.requestScrollToStart()
    }

    override fun requestScrollToLatest() {
        mutableFollowsLatest = true
        listState.requestScrollToStart()
    }

    override fun close() {
        closed = true
        commands.close()
        scope.cancel()
    }

    private fun itemContext(generation: Long): HistoryItemLoadContext =
        HistoryItemLoadContext(
            agentState = agentState,
            scope = scope,
            isGenerationCurrent = {
                !closed && activeGeneration == generation
            },
        )

    private fun materialize(
        descriptor: HistoryItemDescriptor,
        generation: Long = activeGeneration,
    ): HistoryItemViewModel {
        itemCache[descriptor.index]?.let { return it }
        val context = itemContext(generation)
        val item = when (descriptor.kind) {
            HistoryItemKind.Message ->
                MessageHistoryItemViewModelImpl(descriptor.index, descriptor, context)

            HistoryItemKind.Reasoning ->
                ReasoningHistoryItemViewModel(descriptor.index, descriptor.elapsed)

            HistoryItemKind.Tool ->
                ToolHistoryItemViewModelImpl(descriptor.index, descriptor, context)

            HistoryItemKind.Patch ->
                PatchHistoryItemViewModelImpl(descriptor.index, descriptor, context)

            HistoryItemKind.RequestUserInput ->
                RequestUserInputHistoryItemViewModelImpl(descriptor.index, descriptor, context)

            HistoryItemKind.PlanUpdate ->
                PlanUpdateHistoryItemViewModelImpl(descriptor.index, descriptor, context)

            HistoryItemKind.ContextCompaction ->
                ContextCompactionHistoryItemViewModel(descriptor.index, descriptor.elapsed)
        }
        itemCache[descriptor.index] = item
        return item
    }

    private fun materializeChild(
        descriptor: HistoryItemDescriptor,
        generation: Long = activeGeneration,
    ): WorkGroupChildHistoryItemViewModel {
        check(descriptor.isFoldable()) {
            "Only foldable descriptors can be nested in a history work group."
        }
        return materialize(descriptor, generation) as WorkGroupChildHistoryItemViewModel
    }

    private fun materializeProjection(
        item: HistoryProjectionItem,
        generation: Long = activeGeneration,
    ): HistoryItemViewModel = when (item) {
        is HistoryProjectionItem.Stable -> materialize(item.descriptor, generation)
        is HistoryProjectionItem.WorkGroup -> {
            val key = GroupKey(
                oldestIndex = item.descriptors.last().index,
                newestIndex = item.descriptors.first().index,
            )
            groupCache.getOrPut(key) {
                WorkGroupHistoryItemViewModelImpl(
                    descriptors = item.descriptors,
                    groupElapsed = item.groupElapsed,
                    context = itemContext(generation),
                    childFactory = { descriptor ->
                        materializeChild(descriptor, generation)
                    },
                )
            }
        }
    }

    private suspend fun runHistoryLoop() {
        var initialized = false
        var observedLatestIndex = -1
        var nextOlderIndex: Int? = null
        var lastInvalidation: Pair<Int, Int>? = null
        var newestOpenItems: List<HistoryProjectionItem> = emptyList()
        var newestTimeMarkers: List<TurnTimeMarkerHistoryItemViewModel> = emptyList()
        var sealedSequence: HistorySequence = HistorySequence.Empty
        var activeTurn = runningTurn.value != null

        fun publishCurrent(generation: Long = mutableHistoryItems.value.generation) {
            var sequence = HistorySequence.concat(
                HistorySequence.of(
                    newestOpenItems.map { materializeProjection(it, generation) },
                ),
                sealedSequence,
            )
            newestTimeMarkers.forEach { marker ->
                sequence = sequence.insert(marker)
            }
            mutableLatestTurnTimeMarker.value?.let { marker ->
                sequence = sequence.insert(marker)
            }
            publishHistoryItems(sequence, generation)
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
            if (previousTimeMarker != timeMarker) publishCurrent()
        }

        suspend fun reload(latestIndex: Int, invalidate: Boolean) {
            val currentGeneration = mutableHistoryItems.value.generation
            val reloadGeneration = if (invalidate) {
                check(currentGeneration < Long.MAX_VALUE) { "History generations are exhausted." }
                currentGeneration + 1
            } else {
                currentGeneration
            }
            if (invalidate) {
                activeGeneration = reloadGeneration
                itemCache.clear()
                groupCache.clear()
                timelineCursor.reset()
            } else {
                activeGeneration = reloadGeneration
            }
            timelineCursor.refreshThrough(latestIndex)
            newestTimeMarkers = emptyList()
            newestOpenItems = emptyList()
            sealedSequence = HistorySequence.Empty
            publishHistoryItems(
                sequence = HistorySequence.Empty,
                generation = reloadGeneration,
            )
            mutableLoadState.value = AgentHistoryLoadState.Initializing
            val batch = loadBatch(latestIndex)
            val projection = projectNewestHistory(batch.items, timelineCursor.endIndexes())
            observedLatestIndex = latestIndex
            nextOlderIndex = batch.nextOlderIndex
            initialized = true
            newestOpenItems = projection.openItems
            sealedSequence = HistorySequence.of(
                timelineCursor.mergeTimeline(
                    projection.sealedItems.map {
                        materializeProjection(it, reloadGeneration)
                    },
                ).map { entry -> entry.item },
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
            val newestLoaded = current.newestStableIndex
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
                val projectionInput = ArrayList<HistoryItemDescriptor>(
                    additions.size + newestOpenItems.size,
                )
                projectionInput += additions
                projectionInput += newestOpenItems.flatMap { it.descriptors() }
                val projection = projectNewestHistory(
                    projectionInput,
                    timelineCursor.endIndexes(),
                )
                newestOpenItems = projection.openItems
                val projectedSealed = timelineCursor.mergeTimeline(
                    projection.sealedItems.map {
                        materializeProjection(it, activeGeneration)
                    },
                ).map { entry -> entry.item }
                val mergedSealed = (
                    newestTimeMarkers + projectedSealed
                    )
                    .distinctBy { item ->
                        when (item) {
                            is TurnTimeMarkerHistoryItemViewModel ->
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
                val batch = loadBatch(firstIndex)
                val projectedItems = timelineCursor.mergeTimeline(
                    projectSealedHistory(batch.items, timelineCursor.endIndexes())
                        .map { materializeProjection(it, activeGeneration) },
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

    private suspend fun loadBatch(fromInclusive: Int): LoadedHistoryBatch =
        withContext(Dispatchers.Default) {
            if (fromInclusive < 0) return@withContext LoadedHistoryBatch(emptyList(), null)
            val items = ArrayList<HistoryItemDescriptor>(MaximumHistoryBatchSize)
            var index = agentState.storage.stable.floorToIndex(fromInclusive)
            var projectedItemCount = 0
            while (index != null && projectedItemCount < HistoryBatchSize) {
                val event = agentState.storage.stable[index]
                items += event.toHistoryItemDescriptor(index, historyElapsed(index))
                projectedItemCount += 1
                if (timelineCursor.hasTimeMarkerAtEndIndex(index)) projectedItemCount += 1
                index = agentState.storage.stable.prevIndex(index)
            }
            while (
                index != null &&
                projectedItemCount < MaximumHistoryBatchSize &&
                items.lastOrNull()?.isFoldable() == true
            ) {
                val event = agentState.storage.stable[index]
                items += event.toHistoryItemDescriptor(index, historyElapsed(index))
                projectedItemCount += 1
                if (timelineCursor.hasTimeMarkerAtEndIndex(index)) projectedItemCount += 1
                index = agentState.storage.stable.prevIndex(index)
            }
            LoadedHistoryBatch(items, index)
        }

    private suspend fun historyElapsed(index: Int): Duration {
        val previousIndex = agentState.storage.stable.prevIndex(index) ?: return Duration.ZERO
        val timestamp = agentState.storage.timestamp
        if (
            timestamp.floorToIndex(previousIndex) != previousIndex ||
            timestamp.floorToIndex(index) != index
        ) {
            return Duration.ZERO
        }
        return (timestamp[index] - timestamp[previousIndex])
            .takeIf { it >= Duration.ZERO && it.isFinite() }
            ?: Duration.ZERO
    }

    private suspend fun loadNewerThan(
        fromInclusive: Int,
        exclusiveBoundary: Int,
    ): List<HistoryItemDescriptor> = withContext(Dispatchers.Default) {
        val items = mutableListOf<HistoryItemDescriptor>()
        var index: Int? = fromInclusive
        while (index != null && index > exclusiveBoundary) {
            val event = agentState.storage.stable[index]
            items += event.toHistoryItemDescriptor(index, historyElapsed(index))
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
        activeGeneration = generation
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

internal sealed interface HistoryProjectionItem {
    data class Stable(val descriptor: HistoryItemDescriptor) : HistoryProjectionItem

    data class WorkGroup(
        val descriptors: List<HistoryItemDescriptor>,
        val groupElapsed: Duration,
    ) : HistoryProjectionItem
}

internal data class NewestHistoryProjection(
    val openItems: List<HistoryProjectionItem>,
    val sealedItems: List<HistoryProjectionItem>,
)

internal fun projectNewestHistory(
    items: List<HistoryItemDescriptor>,
    turnEndIndexes: Set<Int> = emptySet(),
): NewestHistoryProjection {
    var openItemCount = 0
    while (
        openItemCount < items.size &&
        items[openItemCount].isFoldable() &&
        (
            openItemCount == 0 ||
                items[openItemCount].index !in turnEndIndexes
            )
    ) {
        openItemCount += 1
    }
    return NewestHistoryProjection(
        openItems = items.subList(0, openItemCount).map(HistoryProjectionItem::Stable),
        sealedItems = projectSealedHistory(
            items = items.subList(openItemCount, items.size),
            turnEndIndexes = turnEndIndexes,
        ),
    )
}

internal fun projectSealedHistory(
    items: List<HistoryItemDescriptor>,
    turnEndIndexes: Set<Int> = emptySet(),
): List<HistoryProjectionItem> {
    if (items.isEmpty()) return emptyList()
    val projected = ArrayList<HistoryProjectionItem>(items.size)
    var position = 0
    while (position < items.size) {
        val first = items[position]
        if (!first.isFoldable()) {
            projected += HistoryProjectionItem.Stable(first)
            position += 1
            continue
        }

        val start = position
        position += 1
        while (
            position < items.size &&
            items[position].isFoldable() &&
            items[position].index !in turnEndIndexes
        ) {
            position += 1
        }
        if (position - start == 1) {
            projected += HistoryProjectionItem.Stable(first)
        } else {
            val descriptors = items.subList(start, position).toList()
            projected += HistoryProjectionItem.WorkGroup(
                descriptors = descriptors,
                groupElapsed = groupElapsed(descriptors),
            )
        }
    }
    return projected
}

private fun groupElapsed(descriptors: List<HistoryItemDescriptor>): Duration {
    // Each child duration starts at the preceding stable timestamp. Summing the contiguous run
    // therefore gives the interval represented by the group, including the gap before its oldest
    // child.
    return descriptors.fold(Duration.ZERO) { total, descriptor ->
        total + descriptor.elapsed
    }
}

private fun HistoryProjectionItem.descriptors(): List<HistoryItemDescriptor> = when (this) {
    is HistoryProjectionItem.Stable -> listOf(descriptor)
    is HistoryProjectionItem.WorkGroup -> descriptors
}

private fun HistoryProjectionItem.descriptorOrNull(): HistoryItemDescriptor? = when (this) {
    is HistoryProjectionItem.Stable -> descriptor
    is HistoryProjectionItem.WorkGroup -> null
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

private val HistoryItemViewModel.storageIndexOrNull: Int?
    get() = when (this) {
        is MessageHistoryItemViewModel -> index
        is ReasoningHistoryItemViewModel -> index
        is ToolHistoryItemViewModel -> index
        is RequestUserInputHistoryItemViewModel -> index
        is PatchHistoryItemViewModel -> index
        is PlanUpdateHistoryItemViewModel -> index
        is ContextCompactionHistoryItemViewModel -> index
        is TurnTimeMarkerHistoryItemViewModel,
        is WorkGroupHistoryItemViewModel,
            -> null
    }

private val HistoryItemViewModel.newestStoredIndex: Int
    get() = when (this) {
        is WorkGroupHistoryItemViewModel -> indexRange.last
        is TurnTimeMarkerHistoryItemViewModel -> endIndex
        else -> storageIndexOrNull ?: error("History item has no stable index.")
    }

private val HistoryItemViewModel.newestOrderKey: Long
    get() = when (this) {
        is WorkGroupHistoryItemViewModel -> stableOrderKey(indexRange.last)
        is TurnTimeMarkerHistoryItemViewModel -> timeMarkerOrderKey(endIndex)
        else -> stableOrderKey(storageIndexOrNull ?: error("History item has no index."))
    }

private fun HistoryItemViewModel.isFoldable(): Boolean =
    this is WorkGroupChildHistoryItemViewModel

private fun stableOrderKey(index: Int): Long = index.toLong() * 2L + 1L

private fun timeMarkerOrderKey(endIndex: Int): Long = endIndex.toLong() * 2L + 2L

private data class LoadedHistoryBatch(
    val items: List<HistoryItemDescriptor>,
    val nextOlderIndex: Int?,
)

private data class GroupKey(
    val oldestIndex: Int,
    val newestIndex: Int,
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
        override val item: TurnTimeMarkerHistoryItemViewModel,
    ) : HistoryTimelineEntry {
        override val sourceOrderKey: Long = sourceIndex.toLong() * 2L
    }
}

private class HistoryTimelineCursor(
    private val agentState: KodexAgentState,
) {
    private val markers = mutableListOf<TurnMarker>()
    private val boundaries = mutableListOf<TurnBoundary>()
    private val boundariesByEndIndex = mutableMapOf<Int, MutableList<TurnBoundary>>()
    private val turnEndIndexes = mutableSetOf<Int>()
    private val timeMarkerCache =
        mutableMapOf<Pair<Int, Int>, TurnTimeMarkerHistoryItemViewModel?>()
    private var scannedSettingsIndex: Int? = null
    private var scannedThrough: Int = -1
    private var previousTurnId: String? = null

    suspend fun refreshThrough(latestIndex: Int): List<TurnBoundary> =
        withContext(Dispatchers.Default) {
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
                HistoryTimelineEntry.Stable(item.newestStoredIndex, item)
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
            result += HistoryTimelineEntry.Stable(item.newestStoredIndex, item)
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
    ): List<TurnTimeMarkerHistoryItemViewModel> =
        boundaries.mapNotNull { boundary -> timeMarkerFor(boundary) }

    suspend fun latestTurnTimeMarker(
        latestIndex: Int,
    ): TurnTimeMarkerHistoryItemViewModel? = withContext(Dispatchers.Default) {
        val marker = markers.lastOrNull() ?: return@withContext null
        val endIndex = agentState.storage.stable.floorToIndex(latestIndex)
            ?.takeIf { it > marker.index }
            ?: return@withContext null
        val cacheKey = marker.index to endIndex
        if (cacheKey in timeMarkerCache) return@withContext timeMarkerCache[cacheKey]
        val startTimestamp = startTimestamp(marker.index, endIndex)
            ?: return@withContext null
        val duration = exactTimestamp(endIndex)?.let { timestamp -> timestamp - startTimestamp }
            ?: return@withContext null
        val result = duration
            .takeIf { it >= Duration.ZERO && it.isFinite() }
            ?.let {
                TurnTimeMarkerHistoryItemViewModel(
                    markerIndex = marker.index,
                    endIndex = endIndex,
                    duration = it,
                )
            }
        timeMarkerCache[cacheKey] = result
        result
    }

    suspend fun activeTurnDuration(latestIndex: Int): Duration? =
        withContext(Dispatchers.Default) {
            val marker = markers.lastOrNull() ?: return@withContext null
            val latestStableIndex = agentState.storage.stable.floorToIndex(latestIndex)
            val startTimestamp = startTimestamp(marker.index, latestStableIndex)
                ?: return@withContext null
            (Clock.System.now() - startTimestamp)
                .takeIf { it >= Duration.ZERO && it.isFinite() }
        }

    private suspend fun timeMarkerFor(
        boundary: TurnBoundary,
    ): TurnTimeMarkerHistoryItemViewModel? {
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
            TurnTimeMarkerHistoryItemViewModel(
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
        item.ensureLoaded()
        onOlderDemand(this, index)
        return item
    }
}

/**
 * Immutable balanced rope. Publishing a batch costs `O(log n)` structural nodes and indexed reads
 * cost `O(log n)` while retaining exact item instances.
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
                is WorkGroupHistoryItemViewModel -> HistorySequenceEntry(
                    item = item,
                    newestOrderKey = stableOrderKey(item.indexRange.last),
                    oldestOrderKey = stableOrderKey(item.indexRange.first),
                    newestStableIndex = item.indexRange.last,
                    oldestStableIndex = item.indexRange.first,
                )

                is TurnTimeMarkerHistoryItemViewModel -> HistorySequenceEntry(
                    item = item,
                    newestOrderKey = timeMarkerOrderKey(item.endIndex),
                    oldestOrderKey = timeMarkerOrderKey(item.endIndex),
                    newestStableIndex = null,
                    oldestStableIndex = null,
                )

                else -> {
                    val index = item.storageIndexOrNull
                        ?: error("A stable history item must have an index.")
                    HistorySequenceEntry(
                        item = item,
                        newestOrderKey = stableOrderKey(index),
                        oldestOrderKey = stableOrderKey(index),
                        newestStableIndex = index,
                        oldestStableIndex = index,
                    )
                }
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

        override fun containsStableIndex(index: Int): Boolean = false

        override fun insert(item: HistoryItemViewModel): HistorySequence = of(listOf(item))
    }

    class Leaf(
        val entries: List<HistorySequenceEntry>,
    ) : HistorySequence {
        init {
            require(entries.isNotEmpty())
            require(entries.size <= HistoryLeafSize)
            require(entries.zipWithNext().all { (newer, older) ->
                newer.oldestOrderKey > older.newestOrderKey
            })
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
            val position = entries.indexOfFirst { existing ->
                existing.newestOrderKey < entry.newestOrderKey
            }.takeIf { it >= 0 } ?: entries.size
            val updated = entries.toMutableList().apply { add(position, entry) }
            return if (updated.size <= HistoryLeafSize) Leaf(updated)
            else Branch(Leaf(updated.take(HistoryLeafSize)), Leaf(updated.drop(HistoryLeafSize)))
        }
    }

    class Branch(
        val left: HistorySequence,
        val right: HistorySequence,
    ) : HistorySequence {
        init {
            require(left !== Empty && right !== Empty)
            require(left.oldestOrderKey > right.newestOrderKey)
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
                entry.newestOrderKey < left.oldestOrderKey -> concat(left, right.insert(item))
                else -> concat(left.insert(item), right)
            }
        }
    }

    companion object {
        fun of(items: List<HistoryItemViewModel>): HistorySequence {
            var result: HistorySequence = Empty
            items.map(HistorySequenceEntry.Companion::of)
                .chunked(HistoryLeafSize)
                .forEach { chunk -> result = concat(result, Leaf(chunk)) }
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

private fun checkedHistorySize(left: Int, right: Int): Int {
    val size = left.toLong() + right
    require(size <= Int.MAX_VALUE) { "History item count exceeds Int.MAX_VALUE." }
    return size.toInt()
}
