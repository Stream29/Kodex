package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
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
import io.github.stream29.kodex.app.history.contract.item.WorkGroupChildHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Newest-first History state backed by a bounded, index-driven local window. */
internal class AgentHistoryViewModelImpl(
    private val agentState: KodexAgentState,
    private val scope: CoroutineScope,
    private val runningTurn: StateFlow<Job?>,
) : AgentHistoryViewModel {
    private val commands = Channel<HistoryCommand>(capacity = Channel.BUFFERED)
    private val olderDemandPending = MutableStateFlow(false)
    private val newerDemandPending = MutableStateFlow(false)
    private val turnDurationResolver = HistoryTurnDurationResolver(agentState.storage)
    private var activeGeneration: Long = 0
    private var closed = false

    private val itemCache = mutableMapOf<Int, HistoryItemViewModel>()
    private val groupCache = mutableMapOf<GroupKey, WorkGroupHistoryItemViewModelImpl>()
    private val mutableHistoryItems = MutableStateFlow(
        HistoryItemWindowImpl(
            generation = 0,
            items = emptyList(),
            hasOlder = false,
            hasNewer = false,
            onOlderDemand = ::registerOlderDemand,
            onNewerDemand = ::registerNewerDemand,
        ),
    )
    private val mutableLoadState =
        MutableStateFlow<AgentHistoryLoadState>(AgentHistoryLoadState.Initializing)
    private val mutablePendingTools = MutableStateFlow<List<UnstableCleanEvent>>(emptyList())
    private val mutableStreamingItem = MutableStateFlow<HistoryStreamingItem?>(null)
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
                } else if (!canScrollForward && !mutableHistoryItems.value.hasNewer) {
                    mutableFollowsLatest = true
                }
            }
        }
    }

    private fun registerOlderDemand(
        window: HistoryItemWindowImpl,
    ) {
        if (
            mutableHistoryItems.value === window &&
            window.hasOlder &&
            mutableLoadState.value == AgentHistoryLoadState.Ready &&
            olderDemandPending.compareAndSet(expect = false, update = true)
        ) {
            if (commands.trySend(HistoryCommand.LoadOlder).isFailure) {
                olderDemandPending.value = false
            }
        }
    }

    private fun registerNewerDemand(
        window: HistoryItemWindowImpl,
    ) {
        if (
            mutableHistoryItems.value === window &&
            window.hasNewer &&
            mutableLoadState.value == AgentHistoryLoadState.Ready &&
            newerDemandPending.compareAndSet(expect = false, update = true)
        ) {
            if (commands.trySend(HistoryCommand.LoadNewer).isFailure) {
                newerDemandPending.value = false
            }
        }
    }

    override fun contains(generation: Long, storageIndex: Int): Boolean {
        val window = mutableHistoryItems.value
        return generation == window.generation &&
            window.containsStableIndex(storageIndex)
    }

    override fun notifyContentChanged() {
        if (mutableFollowsLatest) listState.requestScrollToStart()
    }

    override fun requestScrollToLatest() {
        mutableFollowsLatest = true
        commands.trySend(HistoryCommand.JumpToLatest)
    }

    override fun requestScrollToStorageIndex(storageIndex: Int) {
        commands.trySend(HistoryCommand.SeekToStorageIndex(storageIndex))
    }

    override fun close() {
        closed = true
        commands.close()
        releaseAllCachedItems()
        scope.cancel()
    }

    private fun itemContext(generation: Long): HistoryItemLoadContext =
        HistoryItemLoadContext(
            agentState = agentState,
            scope = scope,
            isGenerationCurrent = {
                !closed && activeGeneration == generation
            },
            turnDurationResolver = turnDurationResolver,
        )

    private fun materialize(
        descriptor: HistoryItemDescriptor,
        generation: Long = activeGeneration,
    ): HistoryItemViewModel {
        itemCache[descriptor.index]?.let { return it }
        return createItem(descriptor, generation).also { item ->
            itemCache[descriptor.index] = item
        }
    }

    private fun createItem(
        descriptor: HistoryItemDescriptor,
        generation: Long,
    ): HistoryItemViewModel {
        val context = itemContext(generation)
        return when (descriptor.kind) {
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
    }

    private fun materializeProjection(
        item: HistoryProjectionItem,
        generation: Long = activeGeneration,
    ): HistoryItemViewModel = when (item) {
        is HistoryProjectionItem.Stable -> materialize(item.descriptor, generation)
        is HistoryProjectionItem.WorkGroup -> {
            itemCache.keys
                .filter { index -> index in item.indexRange }
                .forEach { index -> itemCache.remove(index)?.release() }
            val key = GroupKey(
                oldestIndex = item.indexRange.first,
                newestIndex = item.indexRange.last,
            )
            groupCache.getOrPut(key) {
                WorkGroupHistoryItemViewModelImpl(
                    indexRange = item.indexRange,
                    itemCount = item.itemCount,
                    groupElapsed = item.elapsed,
                    context = itemContext(generation),
                    childFactory = {
                        materializeGroupChildren(item, generation)
                    },
                )
            }
        }
    }

    private suspend fun materializeGroupChildren(
        group: HistoryProjectionItem.WorkGroup,
        generation: Long,
    ): List<WorkGroupChildHistoryItemViewModel> = withContext(Dispatchers.Default) {
        val values = agentState.storage.work.valuesIn(group.indexRange)
        check(values.size == group.itemCount) {
            "History work group ${group.indexRange} expected ${group.itemCount} values, " +
                "but storage returned ${values.size}."
        }
        var previousIndex = agentState.storage.visibleStableIndexAtOrBefore(
            group.indexRange.first - 1,
        )
        val descriptors = values.map { (index, event) ->
            event.toHistoryItemDescriptor(
                index = index,
                source = HistoryItemSource.Work,
                elapsed = agentState.storage.elapsedBetween(previousIndex, index),
            ).also {
                previousIndex = index
            }
        }
        descriptors.asReversed().map { descriptor ->
            check(descriptor.isFoldable()) {
                "Only foldable work events can be nested in a History work group."
            }
            createItem(descriptor, generation) as WorkGroupChildHistoryItemViewModel
        }
    }

    private fun pruneCaches(items: List<HistoryProjectionItem>) {
        val retainedIndexes = items.mapNotNullTo(mutableSetOf()) { item ->
            (item as? HistoryProjectionItem.Stable)?.descriptor?.index
        }
        itemCache.keys
            .filterNot(retainedIndexes::contains)
            .forEach { index -> itemCache.remove(index)?.release() }

        val retainedGroups = items.mapNotNullTo(mutableSetOf()) { item ->
            (item as? HistoryProjectionItem.WorkGroup)?.let { group ->
                GroupKey(group.indexRange.first, group.indexRange.last)
            }
        }
        groupCache.keys
            .filterNot(retainedGroups::contains)
            .forEach { key -> groupCache.remove(key)?.collapse() }
    }

    private fun materializeChunk(
        fromInclusive: Int,
        chunk: LoadedHistoryChunk,
        generation: Long,
    ): HistoryWindowChunk = HistoryWindowChunk(
        fromInclusive = fromInclusive,
        projections = chunk.items,
        items = chunk.items.map { item -> materializeProjection(item, generation) },
    )

    private fun visibleChunks(chunks: List<HistoryWindowChunk>): Set<HistoryWindowChunk> {
        val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { item -> item.key }
        if (visibleKeys.isEmpty()) return emptySet()
        return chunks.filterTo(mutableSetOf()) { chunk ->
            chunk.items.any { item -> visibleKeys.any { key -> key === item } }
        }
    }

    private fun captureViewportAnchor(
        chunks: List<HistoryWindowChunk>,
    ): HistoryViewportAnchor? {
        val anchorInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index == listState.firstVisibleItemIndex
        } ?: return null
        val anchorItem = anchorInfo.key as? HistoryItemViewModel ?: return null
        if (chunks.none { chunk -> chunk.items.any { item -> item === anchorItem } }) return null
        return HistoryViewportAnchor(
            item = anchorItem,
            scrollOffset = listState.firstVisibleItemScrollOffset,
        )
    }

    private fun releaseAllCachedItems() {
        itemCache.values.forEach(HistoryItemViewModel::release)
        itemCache.clear()
        groupCache.values.forEach(WorkGroupHistoryItemViewModelImpl::collapse)
        groupCache.clear()
    }

    private suspend fun runHistoryLoop() {
        var initialized = false
        var observedLatestIndex = -1
        var observedTurnIndexEntry: Int? = null
        var nextOlderIndex: Int? = null
        var hasNewer = false
        var lastInvalidation: Pair<Int, Int>? = null
        var activeTurn = runningTurn.value != null
        var activeTurnStart: Instant? = null
        var chunks: List<HistoryWindowChunk> = emptyList()

        fun publishActiveTurnDuration() {
            mutableActiveTurnDuration.value = if (activeTurn) {
                activeTurnStart?.let { start ->
                    (Clock.System.now() - start)
                        .takeIf { duration ->
                            duration >= Duration.ZERO && duration.isFinite()
                        }
                }
            } else {
                null
            }
        }

        suspend fun refreshActiveTurnStart(force: Boolean = false) {
            val currentIndexEntry = if (observedLatestIndex >= 0) {
                agentState.storage.index.floorToIndex(observedLatestIndex)
            } else {
                null
            }
            if (!activeTurn) {
                activeTurnStart = null
                observedTurnIndexEntry = currentIndexEntry
                publishActiveTurnDuration()
                return
            }
            if (force || currentIndexEntry != observedTurnIndexEntry) {
                activeTurnStart = turnDurationResolver.activeTurnStartTimestamp(
                    observedLatestIndex,
                )
                observedTurnIndexEntry = currentIndexEntry
            }
            publishActiveTurnDuration()
        }

        suspend fun replaceWindow(
            latestIndex: Int,
            invalidate: Boolean,
        ) {
            val currentGeneration = mutableHistoryItems.value.generation
            val replacementGeneration = if (invalidate) {
                check(currentGeneration < Long.MAX_VALUE) {
                    "History generations are exhausted."
                }
                currentGeneration + 1
            } else {
                currentGeneration
            }
            if (!initialized || invalidate) {
                mutableLoadState.value = AgentHistoryLoadState.Initializing
            }
            if (invalidate) {
                activeGeneration = replacementGeneration
                releaseAllCachedItems()
                chunks = emptyList()
                publishHistoryItems(
                    chunks = chunks,
                    generation = replacementGeneration,
                    hasOlder = false,
                    hasNewer = false,
                )
            } else {
                activeGeneration = replacementGeneration
            }

            val batch = withContext(Dispatchers.Default) {
                agentState.storage.readHistoryChunk(
                    fromInclusive = latestIndex,
                    snapshotIndex = latestIndex,
                )
            }
            observedLatestIndex = latestIndex
            nextOlderIndex = batch.nextOlderIndex
            hasNewer = false
            initialized = true
            chunks = if (batch.items.isEmpty()) {
                emptyList()
            } else {
                listOf(materializeChunk(latestIndex, batch, replacementGeneration))
            }
            pruneCaches(chunks.flatMap { chunk -> chunk.projections })
            publishHistoryItems(
                chunks = chunks,
                generation = replacementGeneration,
                hasOlder = nextOlderIndex != null,
                hasNewer = false,
            )
            mutableLoadState.value = AgentHistoryLoadState.Ready
            refreshActiveTurnStart(force = invalidate)
        }

        suspend fun refresh(latestIndex: Int) {
            if (!initialized) {
                replaceWindow(latestIndex, invalidate = false)
                return
            }
            if (latestIndex < observedLatestIndex) {
                lastInvalidation = observedLatestIndex to latestIndex
                replaceWindow(latestIndex, invalidate = true)
                return
            }
            if (latestIndex == observedLatestIndex) return
            if (!mutableFollowsLatest || hasNewer) {
                observedLatestIndex = latestIndex
                hasNewer = chunks.isNotEmpty()
                publishHistoryItems(
                    chunks = chunks,
                    hasOlder = nextOlderIndex != null,
                    hasNewer = hasNewer,
                )
                mutableLoadState.value = AgentHistoryLoadState.Ready
                refreshActiveTurnStart()
                return
            }

            if (chunks.isEmpty()) {
                replaceWindow(latestIndex, invalidate = false)
                return
            }
            val visibleChunks = visibleChunks(chunks)
            val batch = withContext(Dispatchers.Default) {
                agentState.storage.readHistoryChunk(
                    fromInclusive = latestIndex,
                    snapshotIndex = latestIndex,
                )
            }
            observedLatestIndex = latestIndex
            if (batch.items.isEmpty()) {
                refreshActiveTurnStart()
                return
            }
            val newChunk = materializeChunk(latestIndex, batch, activeGeneration)
            val retained = chunks.dropWhile { chunk ->
                chunk.newestStorageIndex >= newChunk.oldestStorageIndex
            }
            var combined = listOf(newChunk) + retained
            val lastVisibleChunk = combined.indexOfLast(visibleChunks::contains)
            val keepCount = if (lastVisibleChunk < 0) {
                1
            } else {
                (lastVisibleChunk + 2).coerceAtMost(combined.size)
            }
            val removedOlder = combined.drop(keepCount)
            combined = combined.take(keepCount)
            chunks = combined
            if (removedOlder.isNotEmpty()) {
                nextOlderIndex = removedOlder.first().fromInclusive
            } else if (retained.isEmpty()) {
                nextOlderIndex = batch.nextOlderIndex
            }
            hasNewer = false
            pruneCaches(chunks.flatMap { chunk -> chunk.projections })
            publishHistoryItems(
                chunks = chunks,
                hasOlder = nextOlderIndex != null,
                hasNewer = false,
            )
            mutableLoadState.value = AgentHistoryLoadState.Ready
            refreshActiveTurnStart()
        }

        suspend fun loadOlder() {
            try {
                val fromInclusive = nextOlderIndex
                if (fromInclusive == null) {
                    mutableLoadState.value = AgentHistoryLoadState.Ready
                    return
                }
                mutableLoadState.value = AgentHistoryLoadState.LoadingOlder
                val viewportAnchor = captureViewportAnchor(chunks)
                val visibleChunks = visibleChunks(chunks)
                val batch = withContext(Dispatchers.Default) {
                    agentState.storage.readHistoryChunk(
                        fromInclusive = fromInclusive,
                        snapshotIndex = observedLatestIndex,
                    )
                }
                if (batch.items.isEmpty()) {
                    nextOlderIndex = batch.nextOlderIndex
                } else {
                    val loaded = materializeChunk(fromInclusive, batch, activeGeneration)
                    var combined = chunks + loaded
                    val firstVisibleChunk = combined.indexOfFirst(visibleChunks::contains)
                    val removeCount = (firstVisibleChunk - 1).coerceAtLeast(0)
                    if (removeCount > 0) {
                        combined = combined.drop(removeCount)
                        hasNewer = true
                    }
                    chunks = combined
                    nextOlderIndex = batch.nextOlderIndex
                }
                pruneCaches(chunks.flatMap { chunk -> chunk.projections })
                publishHistoryItems(
                    chunks = chunks,
                    hasOlder = nextOlderIndex != null,
                    hasNewer = hasNewer,
                    viewportAnchor = viewportAnchor,
                )
                mutableLoadState.value = AgentHistoryLoadState.Ready
                olderDemandPending.value = false
            } finally {
                olderDemandPending.value = false
            }
        }

        suspend fun loadNewer() {
            try {
                val head = chunks.firstOrNull()
                if (head == null) {
                    replaceWindow(observedLatestIndex, invalidate = false)
                    return
                }
                mutableLoadState.value = AgentHistoryLoadState.LoadingNewer
                val viewportAnchor = captureViewportAnchor(chunks)
                val visibleChunks = visibleChunks(chunks)
                val batch = withContext(Dispatchers.Default) {
                    agentState.storage.readNewerHistoryChunk(
                        afterExclusive = head.newestStorageIndex,
                        snapshotIndex = observedLatestIndex,
                    )
                }
                if (batch == null || batch.items.isEmpty()) {
                    hasNewer = false
                } else {
                    val fromInclusive = batch.items.maxOf { item -> item.newestStorageIndex }
                    val loaded = materializeChunk(fromInclusive, batch, activeGeneration)
                    val retained = chunks.dropWhile { chunk ->
                        chunk.newestStorageIndex >= loaded.oldestStorageIndex
                    }
                    var combined = listOf(loaded) + retained
                    val lastVisibleChunk = combined.indexOfLast(visibleChunks::contains)
                    val keepCount = if (lastVisibleChunk < 0) {
                        1
                    } else {
                        (lastVisibleChunk + 2).coerceAtMost(combined.size)
                    }
                    val removedOlder = combined.drop(keepCount)
                    combined = combined.take(keepCount)
                    if (removedOlder.isNotEmpty()) {
                        nextOlderIndex = removedOlder.first().fromInclusive
                    }
                    chunks = combined
                    hasNewer = loaded.newestStorageIndex < observedLatestIndex
                }
                pruneCaches(chunks.flatMap { chunk -> chunk.projections })
                publishHistoryItems(
                    chunks = chunks,
                    hasOlder = nextOlderIndex != null,
                    hasNewer = hasNewer,
                    viewportAnchor = viewportAnchor,
                )
                mutableLoadState.value = AgentHistoryLoadState.Ready
            } finally {
                newerDemandPending.value = false
            }
        }

        suspend fun seekToStorageIndex(storageIndex: Int) {
            val snapshotIndex = agentState.latestIndex.value
            val indexEntry = withContext(Dispatchers.Default) {
                agentState.storage.index.getExact(storageIndex)
            } ?: error("The selected History entry is no longer available.")
            val displayIndex = if (indexEntry is CleanCompactionPoint && storageIndex > 0) {
                storageIndex + 1
            } else {
                storageIndex
            }
            check(displayIndex <= snapshotIndex) {
                "The selected History entry is no longer available."
            }
            mutableLoadState.value = AgentHistoryLoadState.Initializing
            val batch = withContext(Dispatchers.Default) {
                agentState.storage.readHistoryChunk(
                    fromInclusive = displayIndex,
                    snapshotIndex = snapshotIndex,
                )
            }
            check(batch.items.isNotEmpty()) {
                "The selected History entry has no visible History item."
            }
            val loaded = materializeChunk(displayIndex, batch, activeGeneration)
            val newer = withContext(Dispatchers.Default) {
                agentState.storage.readNewerHistoryChunk(
                    afterExclusive = loaded.newestStorageIndex,
                    snapshotIndex = snapshotIndex,
                )
            } != null
            observedLatestIndex = snapshotIndex
            nextOlderIndex = batch.nextOlderIndex
            hasNewer = newer
            initialized = true
            chunks = listOf(loaded)
            mutableFollowsLatest = !newer
            pruneCaches(chunks.flatMap { chunk -> chunk.projections })
            publishHistoryItems(
                chunks = chunks,
                hasOlder = nextOlderIndex != null,
                hasNewer = newer,
            )
            if (newer) {
                val localIndex = loaded.items.indexOfFirst { item ->
                    item.storageIndex == displayIndex
                }.takeIf { index -> index >= 0 } ?: 0
                val transientPrefix =
                    (if (mutableStreamingItem.value == null) 0 else 1) +
                        mutablePendingTools.value.size +
                        1
                listState.scrollToItem(transientPrefix + localIndex)
            }
            mutableLoadState.value = AgentHistoryLoadState.Ready
            refreshActiveTurnStart()
        }

        for (command in commands) {
            try {
                when (command) {
                    is HistoryCommand.Refresh -> refresh(command.latestIndex)
                    HistoryCommand.LoadOlder -> loadOlder()
                    HistoryCommand.LoadNewer -> loadNewer()
                    HistoryCommand.JumpToLatest -> {
                        replaceWindow(observedLatestIndex, invalidate = false)
                        listState.requestScrollToStart()
                    }

                    is HistoryCommand.SeekToStorageIndex ->
                        seekToStorageIndex(command.storageIndex)

                    is HistoryCommand.UpdateLatestTurn -> {
                        val changed = activeTurn != command.active
                        activeTurn = command.active
                        refreshActiveTurnStart(force = changed && activeTurn)
                    }

                    is HistoryCommand.ExternalWriteFinished -> {
                        val invalidation = command.startIndex to command.endIndex
                        if (
                            command.endIndex <= command.startIndex &&
                            invalidation != lastInvalidation
                        ) {
                            lastInvalidation = invalidation
                            replaceWindow(command.endIndex, invalidate = true)
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
                newerDemandPending.value = false
                mutableLoadState.value = AgentHistoryLoadState.Failed(
                    failure.message ?: failure.toString(),
                )
            }
        }
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
        chunks: List<HistoryWindowChunk>,
        generation: Long = mutableHistoryItems.value.generation,
        hasOlder: Boolean,
        hasNewer: Boolean,
        viewportAnchor: HistoryViewportAnchor? = null,
    ) {
        val items = chunks.flatMap { chunk -> chunk.items }
        val current = mutableHistoryItems.value
        if (
            current.generation == generation &&
            current.items.sameIdentities(items) &&
            current.hasOlder == hasOlder &&
            current.hasNewer == hasNewer
        ) {
            return
        }
        activeGeneration = generation
        mutableHistoryItems.value = HistoryItemWindowImpl(
            generation = generation,
            items = items,
            hasOlder = hasOlder,
            hasNewer = hasNewer,
            onOlderDemand = ::registerOlderDemand,
            onNewerDemand = ::registerNewerDemand,
        )
        if (mutableFollowsLatest) {
            listState.requestScrollToStart()
        } else if (viewportAnchor != null) {
            val localIndex = items.indexOfFirst { item -> item === viewportAnchor.item }
            if (localIndex >= 0) {
                val transientPrefix =
                    (if (mutableStreamingItem.value == null) 0 else 1) +
                        mutablePendingTools.value.size +
                        (if (hasNewer) 1 else 0)
                listState.scrollToItem(
                    index = transientPrefix + localIndex,
                    scrollOffset = viewportAnchor.scrollOffset,
                )
            }
        }
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
            } else if (
                !listState.canScrollForward &&
                !mutableHistoryItems.value.hasNewer
            ) {
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
        is MessageHistoryItemViewModel -> index
        is ReasoningHistoryItemViewModel -> index
        is ToolHistoryItemViewModel -> index
        is RequestUserInputHistoryItemViewModel -> index
        is PatchHistoryItemViewModel -> index
        is PlanUpdateHistoryItemViewModel -> index
        is ContextCompactionHistoryItemViewModel -> index
        is WorkGroupHistoryItemViewModel -> indexRange.last
    }

private data class GroupKey(
    val oldestIndex: Int,
    val newestIndex: Int,
)

private val HistoryProjectionItem.oldestStorageIndex: Int
    get() = when (this) {
        is HistoryProjectionItem.Stable -> descriptor.index
        is HistoryProjectionItem.WorkGroup -> indexRange.first
    }

private val HistoryProjectionItem.newestStorageIndex: Int
    get() = when (this) {
        is HistoryProjectionItem.Stable -> descriptor.index
        is HistoryProjectionItem.WorkGroup -> indexRange.last
    }

private sealed interface HistoryCommand {
    data class Refresh(val latestIndex: Int) : HistoryCommand
    data object LoadOlder : HistoryCommand
    data object LoadNewer : HistoryCommand
    data object JumpToLatest : HistoryCommand
    data class SeekToStorageIndex(val storageIndex: Int) : HistoryCommand
    data class UpdateLatestTurn(val active: Boolean) : HistoryCommand
    data class ExternalWriteFinished(val startIndex: Int, val endIndex: Int) : HistoryCommand
}

private class HistoryItemWindowImpl(
    override val generation: Long,
    val items: List<HistoryItemViewModel>,
    override val hasOlder: Boolean,
    override val hasNewer: Boolean,
    private val onOlderDemand: (HistoryItemWindowImpl) -> Unit,
    private val onNewerDemand: (HistoryItemWindowImpl) -> Unit,
) : HistoryItemWindow {
    override val size: Int = items.size

    override fun peek(index: Int): HistoryItemViewModel = items[index]

    override fun get(index: Int): HistoryItemViewModel {
        val item = items[index]
        item.ensureLoaded()
        return item
    }

    override fun requestOlder() {
        onOlderDemand(this)
    }

    override fun requestNewer() {
        onNewerDemand(this)
    }

    fun containsStableIndex(index: Int): Boolean =
        items.any { item ->
            when (item) {
                is WorkGroupHistoryItemViewModel -> index in item.indexRange
                else -> item.storageIndex == index
            }
        }
}

private data class HistoryWindowChunk(
    val fromInclusive: Int,
    val projections: List<HistoryProjectionItem>,
    val items: List<HistoryItemViewModel>,
) {
    init {
        require(projections.isNotEmpty())
        require(projections.size == items.size)
    }

    val newestStorageIndex: Int = projections.first().newestStorageIndex
    val oldestStorageIndex: Int = projections.last().oldestStorageIndex
}

private data class HistoryViewportAnchor(
    val item: HistoryItemViewModel,
    val scrollOffset: Int,
)

private fun List<HistoryItemViewModel>.sameIdentities(
    other: List<HistoryItemViewModel>,
): Boolean =
    size == other.size && indices.all { index -> this[index] === other[index] }
