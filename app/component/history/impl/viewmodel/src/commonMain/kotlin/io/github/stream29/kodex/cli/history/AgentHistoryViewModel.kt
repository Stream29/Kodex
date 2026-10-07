package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemViewModel
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollEffect
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Newest-first History state backed by a bounded, index-driven local window. */
internal class AgentHistoryViewModelImpl(
    private val agentState: AgentHistorySource,
    private val scope: CoroutineScope,
    private val runningTurn: StateFlow<Boolean>,
) : AgentHistoryViewModel {
    private val commands = Channel<HistoryCommand>(capacity = Channel.BUFFERED)
    private val olderDemandPending = MutableStateFlow(false)
    private val newerDemandPending = MutableStateFlow(false)
    private val turnDurationResolver = HistoryTurnDurationResolver(agentState.storage)
    private var activeGeneration: Long = agentState.cacheNonce?.value ?: 0
    private var closed = false

    private val itemCache = mutableMapOf<Int, HistoryItemViewModel>()
    private val groupCache = mutableMapOf<GroupKey, WorkGroupHistoryItemViewModelImpl>()
    private val mutableHistoryItems = MutableStateFlow(
        HistoryItemWindowImpl(
            generation = activeGeneration,
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

    private val mutableFollowsLatest = MutableStateFlow(true)
    override val followsLatest: StateFlow<Boolean> = mutableFollowsLatest.asStateFlow()
    private val mutablePendingScrollEffect = MutableStateFlow<HistoryScrollEffect?>(null)
    override val pendingScrollEffect: StateFlow<HistoryScrollEffect?> =
        mutablePendingScrollEffect.asStateFlow()
    private var navigationIntent: HistoryNavigationIntent? = null
    private val pendingNavigation = MutableStateFlow<HistoryCommand?>(null)
    private var visibleItems: List<HistoryItemViewModel> = emptyList()

    init {
        scope.launch { runHistoryLoop() }
        scope.launch {
            agentState.latestIndex.collect { latestIndex ->
                commands.send(HistoryCommand.Refresh(latestIndex))
            }
        }
        agentState.cacheNonce?.let { nonce ->
            scope.launch {
                nonce.collect { generation ->
                    withdrawNavigation(preserveGeneration = generation)
                    commands.send(HistoryCommand.Invalidate(generation))
                }
            }
        }
        scope.launch {
            val unstable = agentState.storage.unstable as? CachedIndexVersioned<*>
            val changes = if (unstable == null) agentState.latestIndex.map { it to null }
                else combine(agentState.latestIndex, unstable.cacheNonce) { index, nonce -> index to nonce }
            changes.collect { (latestIndex, nonce) ->
                try {
                    val pending = loadPendingTools(latestIndex)
                    if (nonce == unstable?.cacheNonce?.value) publishPendingTools(pending)
                } catch (failure: CacheNonceMismatch) {
                    if (unstable == null) throw failure
                }
            }
        }
        scope.launch {
            var ticker: Job? = null
            try {
                runningTurn.collect { active ->
                    ticker?.cancel()
                    ticker = if (active) {
                        scope.launch {
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
                    } else null
                    if (!active) {
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
                                generation = agentState.cacheNonce?.value,
                            ),
                        )
                    }
                    externalWriteStart = null
                }
            }
        }
    }

    private fun registerOlderDemand(
        window: HistoryItemWindowImpl,
    ) {
        if (
            !closed && mutableHistoryItems.value === window &&
            window.hasOlder &&
            mutableLoadState.value == AgentHistoryLoadState.Ready &&
            olderDemandPending.compareAndSet(expect = false, update = true)
        ) {
            if (commands.trySend(HistoryCommand.LoadOlder(window.generation)).isFailure) {
                olderDemandPending.value = false
            }
        }
    }

    private fun registerNewerDemand(
        window: HistoryItemWindowImpl,
    ) {
        if (
            !closed && mutableHistoryItems.value === window &&
            window.hasNewer &&
            mutableLoadState.value == AgentHistoryLoadState.Ready &&
            newerDemandPending.compareAndSet(expect = false, update = true)
        ) {
            if (commands.trySend(HistoryCommand.LoadNewer(window.generation)).isFailure) {
                newerDemandPending.value = false
            }
        }
    }

    override fun contains(generation: Long, storageIndex: Int): Boolean {
        val window = mutableHistoryItems.value
        return !closed && generation == window.generation &&
            (agentState.cacheNonce?.value?.let { it == generation } != false) &&
            window.containsStableIndex(storageIndex)
    }

    private fun isCurrent(window: HistoryItemWindow): Boolean =
        !closed && mutableHistoryItems.value === window &&
            (agentState.cacheNonce?.value?.let { it == window.generation } != false)

    override fun reportViewport(
        window: HistoryItemWindow,
        visibleItems: List<HistoryItemViewModel>,
    ) {
        if (!isCurrent(window)) return
        val current = mutableHistoryItems.value.items
        if (visibleItems.any { visible -> current.none { it === visible } }) return
        this.visibleItems = visibleItems.toList()
    }

    override fun setFollowsLatest(window: HistoryItemWindow, followsLatest: Boolean) {
        if (!isCurrent(window) || (followsLatest && window.hasNewer)) return
        mutableFollowsLatest.value = followsLatest
    }

    override fun acknowledgeScrollEffect(effect: HistoryScrollEffect) {
        mutablePendingScrollEffect.compareAndSet(effect, null)
    }

    private fun withdrawNavigation(preserveGeneration: Long? = null) {
        if (preserveGeneration == null || navigationIntent?.generation != preserveGeneration) {
            navigationIntent = null
            pendingNavigation.value = null
        }
        mutablePendingScrollEffect.value?.let { effect ->
            if (effect.generation != preserveGeneration) mutablePendingScrollEffect.compareAndSet(effect, null)
        }
    }

    private fun isCurrent(intent: HistoryNavigationIntent): Boolean =
        !closed && navigationIntent === intent &&
            activeGeneration == intent.generation &&
            intent.generation == (agentState.cacheNonce?.value ?: activeGeneration)

    private fun publishScrollEffect(intent: HistoryNavigationIntent, target: HistoryScrollTarget) {
        val window = mutableHistoryItems.value
        if (isCurrent(intent) && intent.generation == window.generation && isCurrent(window)) {
            mutablePendingScrollEffect.value = HistoryScrollEffect(window.generation, target)
        }
    }

    override fun requestScrollToLatest() {
        if (closed) return
        mutableFollowsLatest.value = true
        val intent = HistoryNavigationIntent(agentState.cacheNonce?.value ?: activeGeneration)
        navigationIntent = intent
        mutablePendingScrollEffect.value = null
        pendingNavigation.value = HistoryCommand.JumpToLatest(intent)
        commands.trySend(HistoryCommand.Navigate)
    }

    override fun requestScrollToStorageIndex(storageIndex: Int) {
        if (closed) return
        val intent = HistoryNavigationIntent(agentState.cacheNonce?.value ?: activeGeneration)
        navigationIntent = intent
        mutablePendingScrollEffect.value = null
        pendingNavigation.value = HistoryCommand.SeekToStorageIndex(storageIndex, intent)
        commands.trySend(HistoryCommand.Navigate)
    }

    override fun close() {
        closed = true
        withdrawNavigation()
        visibleItems = emptyList()
        commands.close()
        releaseAllCachedItems()
        scope.cancel()
    }

    private fun itemContext(generation: Long): HistoryItemLoadContext =
        HistoryItemLoadContext(
            agentState = agentState,
            scope = scope,
            isGenerationCurrent = {
                !closed && activeGeneration == generation &&
                    (agentState.cacheNonce?.value?.let { it == generation } != false)
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

            HistoryItemKind.SuggestSubagentTask ->
                SuggestSubagentTaskHistoryItemViewModelImpl(descriptor.index, descriptor, context)

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
        if (visibleItems.isEmpty()) return emptySet()
        return chunks.filterTo(mutableSetOf()) { chunk ->
            chunk.items.any { item -> visibleItems.any { visible -> visible === item } }
        }
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
        var activeTurn = runningTurn.value
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
            val replacementGeneration = agentState.cacheNonce?.value ?: if (invalidate) {
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
                withdrawNavigation(preserveGeneration = replacementGeneration.takeIf {
                    agentState.cacheNonce != null && it != currentGeneration
                })
                visibleItems = emptyList()
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
            if (closed || agentState.cacheNonce?.value?.let { it != replacementGeneration } == true) return
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
            if (!mutableFollowsLatest.value || hasNewer) {
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
            val batch = withContext(Dispatchers.Default) {
                agentState.storage.readHistoryChunk(
                    fromInclusive = latestIndex,
                    snapshotIndex = latestIndex,
                )
            }
            if (closed || agentState.cacheNonce?.value?.let { it != activeGeneration } == true) return
            observedLatestIndex = latestIndex
            if (batch.items.isEmpty()) {
                refreshActiveTurnStart()
                return
            }
            val newChunk = materializeChunk(latestIndex, batch, activeGeneration)
            val retained = chunks.dropWhile { chunk ->
                chunk.newestStorageIndex >= newChunk.oldestStorageIndex
            }
            val visibleChunks = visibleChunks(chunks)
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
                val batch = withContext(Dispatchers.Default) {
                    agentState.storage.readHistoryChunk(
                        fromInclusive = fromInclusive,
                        snapshotIndex = observedLatestIndex,
                    )
                }
                if (closed || agentState.cacheNonce?.value?.let { it != activeGeneration } == true) return
                if (batch.items.isEmpty()) {
                    nextOlderIndex = batch.nextOlderIndex
                } else {
                    val loaded = materializeChunk(fromInclusive, batch, activeGeneration)
                    var combined = chunks + loaded
                    val visibleChunks = visibleChunks(chunks)
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
                )
            } finally {
                olderDemandPending.value = false
            }
            // Ready observers may synchronously request the next page.
            mutableLoadState.value = AgentHistoryLoadState.Ready
        }

        suspend fun loadNewer() {
            try {
                val head = chunks.firstOrNull()
                if (head == null) {
                    replaceWindow(observedLatestIndex, invalidate = false)
                    return
                }
                mutableLoadState.value = AgentHistoryLoadState.LoadingNewer
                val batch = withContext(Dispatchers.Default) {
                    agentState.storage.readNewerHistoryChunk(
                        afterExclusive = head.newestStorageIndex,
                        snapshotIndex = observedLatestIndex,
                    )
                }
                if (closed || agentState.cacheNonce?.value?.let { it != activeGeneration } == true) return
                if (batch == null || batch.items.isEmpty()) {
                    hasNewer = false
                } else {
                    val fromInclusive = batch.items.maxOf { item -> item.newestStorageIndex }
                    val loaded = materializeChunk(fromInclusive, batch, activeGeneration)
                    val retained = chunks.dropWhile { chunk ->
                        chunk.newestStorageIndex >= loaded.oldestStorageIndex
                    }
                    val visibleChunks = visibleChunks(chunks)
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
                )
            } finally {
                newerDemandPending.value = false
            }
            mutableLoadState.value = AgentHistoryLoadState.Ready
        }

        suspend fun seekToStorageIndex(storageIndex: Int, intent: HistoryNavigationIntent) {
            if (!isCurrent(intent)) return
            val snapshotIndex = agentState.latestIndex.value
            val indexEntry = withContext(Dispatchers.Default) {
                agentState.storage.index.getExact(storageIndex)
            }
            if (!isCurrent(intent)) return
            checkNotNull(indexEntry) { "The selected History entry is no longer available." }
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
            val newer = withContext(Dispatchers.Default) {
                agentState.storage.readNewerHistoryChunk(
                    afterExclusive = batch.items.first().newestStorageIndex,
                    snapshotIndex = snapshotIndex,
                )
            } != null
            if (!isCurrent(intent)) return
            val loaded = materializeChunk(displayIndex, batch, activeGeneration)
            observedLatestIndex = snapshotIndex
            nextOlderIndex = batch.nextOlderIndex
            hasNewer = newer
            initialized = true
            chunks = listOf(loaded)
            if (navigationIntent === intent) mutableFollowsLatest.value = !newer
            pruneCaches(chunks.flatMap { chunk -> chunk.projections })
            publishHistoryItems(
                chunks = chunks,
                hasOlder = nextOlderIndex != null,
                hasNewer = newer,
            )
            val target = loaded.items.firstOrNull { it.storageIndex == displayIndex }
                ?: loaded.items.first()
            publishScrollEffect(intent, HistoryScrollTarget.Item(target))
            mutableLoadState.value = AgentHistoryLoadState.Ready
            refreshActiveTurnStart()
        }

        suspend fun synchronizeGeneration() {
            while (!closed) {
                val generation = agentState.cacheNonce?.value ?: return
                if (activeGeneration == generation) return
                replaceWindow(agentState.latestIndex.value, invalidate = true)
            }
        }

        suspend fun navigate(command: HistoryCommand) {
            // Retained navigation can run before the next channel receive as well.
            synchronizeGeneration()
            when (command) {
                is HistoryCommand.JumpToLatest -> if (isCurrent(command.intent)) {
                    replaceWindow(agentState.latestIndex.value, invalidate = false)
                    publishScrollEffect(command.intent, HistoryScrollTarget.Latest)
                }
                is HistoryCommand.SeekToStorageIndex -> seekToStorageIndex(command.storageIndex, command.intent)
                else -> error("Not a navigation command.")
            }
        }

        for (command in commands) {
            try {
                // Invalidation wins over retained navigation, but only withdraws the old nonce.
                // A late queued Invalidate must not release the already-current destination.
                synchronizeGeneration()
                when (command) {
                    is HistoryCommand.Invalidate -> {
                        if (command.generation == agentState.cacheNonce?.value &&
                            (!initialized || activeGeneration != command.generation)
                        ) replaceWindow(agentState.latestIndex.value, invalidate = true)
                    }
                    is HistoryCommand.Refresh -> refresh(
                        if (agentState.cacheNonce == null) command.latestIndex else agentState.latestIndex.value,
                    )
                    is HistoryCommand.LoadOlder -> {
                        if (command.generation == activeGeneration) loadOlder()
                        else olderDemandPending.value = false
                    }
                    is HistoryCommand.LoadNewer -> {
                        if (command.generation == activeGeneration) loadNewer()
                        else newerDemandPending.value = false
                    }
                    HistoryCommand.Navigate -> Unit
                    is HistoryCommand.JumpToLatest, is HistoryCommand.SeekToStorageIndex -> navigate(command)

                    is HistoryCommand.UpdateLatestTurn -> {
                        val changed = activeTurn != command.active
                        activeTurn = command.active
                        refreshActiveTurnStart(force = changed && activeTurn)
                    }

                    is HistoryCommand.ExternalWriteFinished -> {
                        if (command.generation != null) {
                            // The authoritative nonce has already been reconciled above.
                            // Completion of that same write is not another destructive change.
                            // Its captured cursor can also precede later same-nonce appends.
                            if (command.generation == agentState.cacheNonce?.value) {
                                refresh(agentState.latestIndex.value)
                            }
                        } else {
                            // Without a nonce, completion still detects same-cursor rewrites.
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
            // One retained intent, not a journal. A full command buffer itself guarantees
            // another loop iteration; no successful wake admission is needed to retain the intent.
            pendingNavigation.getAndUpdate { null }?.let { navigation ->
                try {
                    navigate(navigation)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    mutableLoadState.value = AgentHistoryLoadState.Failed(failure.message ?: failure.toString())
                }
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
    ) {
        if (closed || agentState.cacheNonce?.value?.let { it != generation } == true) return
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
        visibleItems = visibleItems.filter { visible -> items.any { it === visible } }
        mutablePendingScrollEffect.value?.let { effect ->
            val target = effect.target
            if (effect.generation != generation ||
                (target is HistoryScrollTarget.Item && items.none { it === target.item })
            ) {
                mutablePendingScrollEffect.compareAndSet(effect, null)
            }
        }
    }

    private fun publishPendingTools(pending: List<UnstableCleanEvent>) {
        if (mutablePendingTools.value == pending) return
        mutablePendingTools.value = pending
    }

    private fun publishStreamingItem(item: HistoryStreamingItem?) {
        if (mutableStreamingItem.value == item) return
        mutableStreamingItem.value = item
    }
}

/**
 * Creates the single History owner for [source]'s fixed Agent.
 *
 * [ownerScope] must be a dedicated child scope: [AgentHistoryViewModel.close] cancels it, including
 * paging, streaming observation and item payload reads. [running] belongs to the same Agent and
 * drives elapsed-turn presentation only. The caller retains storage/binding ownership.
 * The returned owner starts structural loading asynchronously; failures are published through
 * `loadState`. Construction does not synchronously read storage or throw for absent history.
 */
public fun createAgentHistoryViewModel(
    source: AgentHistorySource,
    ownerScope: CoroutineScope,
    running: StateFlow<Boolean>,
): AgentHistoryViewModel = AgentHistoryViewModelImpl(source, ownerScope, running)

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
        is SuggestSubagentTaskHistoryItemViewModel -> index
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
    data class Invalidate(val generation: Long) : HistoryCommand
    data class Refresh(val latestIndex: Int) : HistoryCommand
    data class LoadOlder(val generation: Long) : HistoryCommand
    data class LoadNewer(val generation: Long) : HistoryCommand
    data object Navigate : HistoryCommand
    data class JumpToLatest(val intent: HistoryNavigationIntent) : HistoryCommand
    data class SeekToStorageIndex(val storageIndex: Int, val intent: HistoryNavigationIntent) : HistoryCommand
    data class UpdateLatestTurn(val active: Boolean) : HistoryCommand
    data class ExternalWriteFinished(
        val startIndex: Int,
        val endIndex: Int,
        val generation: Long?,
    ) : HistoryCommand
}

private class HistoryNavigationIntent(val generation: Long)

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

private fun List<HistoryItemViewModel>.sameIdentities(
    other: List<HistoryItemViewModel>,
): Boolean =
    size == other.size && indices.all { index -> this[index] === other[index] }
