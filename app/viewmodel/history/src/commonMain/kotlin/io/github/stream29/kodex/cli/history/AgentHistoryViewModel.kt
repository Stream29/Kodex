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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/** Newest-first, demand-extended History View state for one Agent. */
internal class AgentHistoryViewModelImpl(
    private val agentState: KodexAgentState,
    private val scope: CoroutineScope,
) : AgentHistoryViewModel {
    private val commands = Channel<HistoryCommand>(capacity = Channel.BUFFERED)
    private val committedReadSemaphore = Semaphore(HistoryReadParallelism)
    private val olderDemandPending = MutableStateFlow(false)
    private val mutableCommittedItems = MutableStateFlow(
        CommittedHistoryItemWindow(
            generation = 0,
            sequence = HistorySequence.Empty,
            onOlderDemand = ::registerOlderDemand,
        ),
    )
    private val mutableLoadState =
        MutableStateFlow<AgentHistoryLoadState>(AgentHistoryLoadState.Initializing)
    private val mutablePendingTools = MutableStateFlow<List<UnstableCleanEvent>>(emptyList())
    private val mutableStreamingItem = MutableStateFlow<HistoryStreamingItem?>(null)

    override val committedItems: StateFlow<HistoryItemWindow> =
        mutableCommittedItems.asStateFlow()
    override val loadState: StateFlow<AgentHistoryLoadState> = mutableLoadState.asStateFlow()
    override val pendingTools: StateFlow<List<UnstableCleanEvent>> =
        mutablePendingTools.asStateFlow()
    override val streamingItem: StateFlow<HistoryStreamingItem?> =
        mutableStreamingItem.asStateFlow()

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
            agentState.latestIndex.collectLatest { latestIndex ->
                publishPendingTools(loadPendingTools(latestIndex))
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
        window: CommittedHistoryItemWindow,
        index: Int,
    ) {
        if (
            mutableCommittedItems.value === window &&
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
        return committedReadSemaphore.withPermit {
            check(mutableCommittedItems.value.find(index) === item) {
                "Committed history item $index is no longer current."
            }
            withContext(Dispatchers.Default) {
                agentState.storage.stable[index]
            }
        }
    }

    override fun contains(generation: Long, storageIndex: Int): Boolean =
        mutableCommittedItems.value.let { window ->
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
        var sealedSequence: HistorySequence = HistorySequence.Empty

        fun publishCurrent(generation: Long = mutableCommittedItems.value.generation) {
            publishCommitted(
                sequence = HistorySequence.concat(
                    HistorySequence.of(newestOpenItems),
                    sealedSequence,
                ),
                generation = generation,
            )
        }

        suspend fun reload(latestIndex: Int, invalidate: Boolean) {
            val currentGeneration = mutableCommittedItems.value.generation
            val reloadGeneration = if (invalidate) {
                check(currentGeneration < Long.MAX_VALUE) { "History generations are exhausted." }
                currentGeneration + 1
            } else {
                currentGeneration
            }
            newestOpenItems = emptyList()
            sealedSequence = HistorySequence.Empty
            publishCommitted(
                sequence = HistorySequence.Empty,
                generation = reloadGeneration,
            )
            mutableLoadState.value = AgentHistoryLoadState.Initializing
            val batch = loadBatch(latestIndex)
            val projection = projectNewestHistory(batch.items)
            observedLatestIndex = latestIndex
            nextOlderIndex = batch.nextOlderIndex
            initialized = true
            newestOpenItems = projection.openItems
            sealedSequence = HistorySequence.of(projection.sealedItems)
            publishCurrent(reloadGeneration)
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

            val current = mutableCommittedItems.value.sequence
            val newestLoaded = current.takeUnless { it === HistorySequence.Empty }?.newestIndex
            val newestStored = withContext(Dispatchers.Default) {
                agentState.storage.stable.floorToIndex(latestIndex)
            }
            observedLatestIndex = latestIndex
            if (newestStored == null || newestStored == newestLoaded) return
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
                val projection = projectNewestHistory(projectionInput)
                newestOpenItems = projection.openItems
                sealedSequence = HistorySequence.concat(
                    HistorySequence.of(projection.sealedItems),
                    sealedSequence,
                )
                publishCurrent()
            }
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
                val projectedItems = projectSealedHistory(batch.items)
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
            val items = ArrayList<HistoryItemViewModel>(MaximumHistoryBatchSize)
            var index = agentState.storage.stable.floorToIndex(fromInclusive)
            while (index != null && items.size < HistoryBatchSize) {
                items += agentState.storage.stable[index].toHistoryItem(index)
                index = agentState.storage.stable.prevIndex(index)
            }
            while (
                index != null &&
                items.size < MaximumHistoryBatchSize &&
                items.last().isAutomaticallyFoldable
            ) {
                items += agentState.storage.stable[index].toHistoryItem(index)
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

    private fun publishCommitted(
        sequence: HistorySequence,
        generation: Long = mutableCommittedItems.value.generation,
    ) {
        val current = mutableCommittedItems.value
        if (current.generation == generation && current.sequence === sequence) return
        mutableCommittedItems.value = CommittedHistoryItemWindow(
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
): AgentHistoryViewModel = AgentHistoryViewModelImpl(
    agentState = agentState,
    scope = ownerScope,
)

/** Koin-resolved History creator with one exact Agent runtime parameter set. */
@Factory
public class DefaultAgentHistoryViewModelFactory(
    @InjectedParam private val agentState: KodexAgentState,
    @InjectedParam private val ownerScope: CoroutineScope,
) {
    public fun create(): AgentHistoryViewModel =
        createAgentHistoryViewModel(agentState, ownerScope)
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
): NewestHistoryProjection {
    var openItemCount = 0
    while (
        openItemCount < items.size &&
        items[openItemCount].isAutomaticallyFoldable
    ) {
        openItemCount += 1
    }
    return NewestHistoryProjection(
        openItems = items.subList(0, openItemCount).toList(),
        sealedItems = projectSealedHistory(items.subList(openItemCount, items.size)),
    )
}

/** Projects complete or forcibly bounded work runs without retaining decoded stable events. */
internal fun projectSealedHistory(
    items: List<HistoryItemViewModel>,
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
            items[position].isAutomaticallyFoldable
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
        is HistoryItemViewModel.WorkGroup,
            -> false
    }

private val HistoryItemViewModel.newestStorageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> indexRange.last
        else -> storageIndex
    }

private val HistoryItemViewModel.oldestStorageIndex: Int
    get() = when (this) {
        is HistoryItemViewModel.WorkGroup -> indexRange.first
        else -> storageIndex
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

        else -> takeIf { this.storageIndex == storageIndex }
    }
}

private data class LoadedHistoryBatch(
    val items: List<HistoryItemViewModel>,
    val nextOlderIndex: Int?,
)

private sealed interface HistoryCommand {
    data class Refresh(val latestIndex: Int) : HistoryCommand
    data object LoadOlder : HistoryCommand
    data class ExternalWriteFinished(val startIndex: Int, val endIndex: Int) : HistoryCommand
}

private class CommittedHistoryItemWindow(
    override val generation: Long,
    val sequence: HistorySequence,
    private val onOlderDemand: (CommittedHistoryItemWindow, Int) -> Unit,
) : HistoryItemWindow {
    override val size: Int = sequence.size

    override fun peek(index: Int): HistoryItemViewModel = sequence[index]

    override fun get(index: Int): HistoryItemViewModel {
        val item = sequence[index]
        onOlderDemand(this, index)
        return item
    }

    fun find(storageIndex: Int): HistoryItemViewModel? = sequence.find(storageIndex)
}

/**
 * Immutable balanced rope. Publishing a batch costs `O(log n)` structural nodes and indexed reads
 * cost `O(log n)` while retaining exact child instances.
 */
private sealed interface HistorySequence {
    val size: Int
    val height: Int
    val newestIndex: Int
    val oldestIndex: Int

    operator fun get(index: Int): HistoryItemViewModel

    fun find(storageIndex: Int): HistoryItemViewModel?

    fun firstOrNull(): HistoryItemViewModel? = if (size == 0) null else this[0]

    data object Empty : HistorySequence {
        override val size: Int = 0
        override val height: Int = 0
        override val newestIndex: Int
            get() = error("An empty history sequence has no newest index.")
        override val oldestIndex: Int
            get() = error("An empty history sequence has no oldest index.")

        override fun get(index: Int): HistoryItemViewModel =
            throw IndexOutOfBoundsException("History item index $index is out of bounds for 0 items.")

        override fun find(storageIndex: Int): HistoryItemViewModel? = null
    }

    class Leaf(
        val items: List<HistoryItemViewModel>,
    ) : HistorySequence {
        init {
            require(items.isNotEmpty()) { "A history sequence leaf must not be empty." }
            require(items.size <= HistoryLeafSize) {
                "A history sequence leaf cannot exceed $HistoryLeafSize items."
            }
            require(items.zipWithNext().all { (newer, older) ->
                newer.oldestStorageIndex > older.newestStorageIndex
            }) {
                "History sequence items must be strictly newest-first."
            }
        }

        override val size: Int = items.size
        override val height: Int = 1
        override val newestIndex: Int = items.first().newestStorageIndex
        override val oldestIndex: Int = items.last().oldestStorageIndex

        override fun get(index: Int): HistoryItemViewModel = items[index]

        override fun find(storageIndex: Int): HistoryItemViewModel? {
            if (storageIndex !in oldestIndex..newestIndex) return null
            for (item in items) {
                if (storageIndex in item.oldestStorageIndex..item.newestStorageIndex) {
                    return item.find(storageIndex)
                }
            }
            return null
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
            require(left.oldestIndex > right.newestIndex) {
                "History sequence branches must be strictly newest-first."
            }
        }

        override val size: Int = checkedHistorySize(left.size, right.size)
        override val height: Int = maxOf(left.height, right.height) + 1
        override val newestIndex: Int = left.newestIndex
        override val oldestIndex: Int = right.oldestIndex

        override fun get(index: Int): HistoryItemViewModel {
            if (index !in 0 until size) {
                throw IndexOutOfBoundsException(
                    "History item index $index is out of bounds for $size items.",
                )
            }
            return if (index < left.size) left[index] else right[index - left.size]
        }

        override fun find(storageIndex: Int): HistoryItemViewModel? {
            if (storageIndex !in oldestIndex..newestIndex) return null
            return when {
                storageIndex >= left.oldestIndex -> left.find(storageIndex)
                storageIndex <= right.newestIndex -> right.find(storageIndex)
                else -> null
            }
        }
    }

    companion object {
        fun of(items: List<HistoryItemViewModel>): HistorySequence {
            var result: HistorySequence = Empty
            items.chunked(HistoryLeafSize).forEach { chunk ->
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
