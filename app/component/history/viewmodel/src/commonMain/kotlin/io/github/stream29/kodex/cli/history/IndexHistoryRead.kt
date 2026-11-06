package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ceilToIndex
import io.github.stream29.kodex.agentstorage.contract.getIndexEntryExact
import io.github.stream29.kodex.agentstorage.contract.floorToIndex
import io.github.stream29.kodex.agentstorage.contract.getTimestampExact
import io.github.stream29.kodex.openai.MessagePhase
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One first-level History item.
 *
 * The index timeline defines the structure. A sealed work interval is represented by one
 * [WorkGroup] regardless of its work-item count; sealed work payloads are not decoded here.
 */
internal sealed interface HistoryProjectionItem {
    data class Stable(
        val descriptor: HistoryItemDescriptor,
    ) : HistoryProjectionItem

    data class WorkGroup(
        val indexRange: IntRange,
        val itemCount: Int,
        val elapsed: Duration,
    ) : HistoryProjectionItem {
        init {
            require(itemCount > 0)
            require(!indexRange.isEmpty())
        }
    }
}

internal data class LoadedHistoryChunk(
    val items: List<HistoryProjectionItem>,
    /** Inclusive storage index from which the next older structural chunk starts. */
    val nextOlderIndex: Int?,
)

/**
 * Reads the next structural History chunk at or before [fromInclusive].
 *
 * One chunk is either a bounded open-work prefix, or one index anchor together with its preceding
 * sealed work group. The page reader supplies [maxOpenItems]; direct callers default to one.
 */
internal suspend fun KodexAgentStorage.readHistoryChunk(
    fromInclusive: Int,
    snapshotIndex: Int = fromInclusive,
    maxOpenItems: Int = 1,
): LoadedHistoryChunk {
    require(maxOpenItems > 0)
    if (fromInclusive < 0) return LoadedHistoryChunk(emptyList(), null)
    require(snapshotIndex >= fromInclusive) {
        "History snapshot $snapshotIndex cannot precede cursor $fromInclusive."
    }

    val result = ArrayList<HistoryProjectionItem>(2)
    var current = floorToIndex(fromInclusive, AgentCommitType.IndexTypes)?.let { entryIndex ->
        getIndexEntryExact(entryIndex)?.let { entry ->
            IndexedEntry(
                index = entryIndex,
                entry = entry,
            )
        }
    }
    var currentRepresentation = current?.representationAt(this, snapshotIndex)

    val openLowerBound = current?.normalWorkLowerBound() ?: 0
    val openWorkIndexes = if (fromInclusive >= openLowerBound) {
        indexesIn(
            range = openLowerBound..fromInclusive,
            types = AgentCommitType.WorkTypes,
        )
    } else {
        emptyList()
    }
    if (openWorkIndexes.isNotEmpty()) {
        val selectedIndexes = openWorkIndexes.takeLast(maxOpenItems)
        val predecessorIndex = openWorkIndexes.getOrNull(openWorkIndexes.size - selectedIndexes.size - 1)
            ?: currentRepresentation?.displayIndex
        val selectedCommits = getCommitsIn(
            range = selectedIndexes.first()..selectedIndexes.last(),
            types = AgentCommitType.WorkTypes,
        ).asReversed()
        result += individualWorkItems(
            commitsNewestFirst = selectedCommits,
            oldestPredecessorIndex = predecessorIndex,
        )
        return LoadedHistoryChunk(
            items = result,
            nextOlderIndex = predecessorIndex
                ?: current?.cursorIndex(currentRepresentation),
        )
    }

    while (current != null) {
        val older = current.previous(this)
        val olderRepresentation = older?.representationAt(this, Int.MAX_VALUE)
        val sealedWorkIndexes = sealedWorkIndexes(
            older = older,
            newerIndex = current.index,
        )
        val predecessorIndex = sealedWorkIndexes.lastOrNull()
            ?: olderRepresentation?.displayIndex

        currentRepresentation?.toProjection(this, predecessorIndex)?.let(result::add)
        if (sealedWorkIndexes.isNotEmpty()) {
            result += HistoryProjectionItem.WorkGroup(
                indexRange = sealedWorkIndexes.first()..sealedWorkIndexes.last(),
                itemCount = sealedWorkIndexes.size,
                elapsed = elapsedBetween(
                    previousIndex = olderRepresentation?.displayIndex,
                    currentIndex = sealedWorkIndexes.last(),
                ),
            )
        }

        if (result.isNotEmpty()) {
            return LoadedHistoryChunk(
                items = result,
                nextOlderIndex = older?.cursorIndex(olderRepresentation),
            )
        }
        current = older
        currentRepresentation = olderRepresentation
    }
    return LoadedHistoryChunk(result, null)
}

/**
 * Reads the structural chunk immediately newer than [afterExclusive].
 *
 * The index timeline seals every preceding work interval, so seeking to the next index anchor
 * returns that anchor and its whole collapsed work group without walking through the interval.
 * When there is no newer index anchor, at most [maxOpenItems] newer open-work items are returned.
 */
internal suspend fun KodexAgentStorage.readNewerHistoryChunk(
    afterExclusive: Int,
    snapshotIndex: Int,
    maxOpenItems: Int = 1,
): LoadedHistoryChunk? {
    require(maxOpenItems > 0)
    if (afterExclusive >= snapshotIndex) return null
    val nextIndexAnchor = ceilToIndex(
        index = afterExclusive + 1,
        types = AgentCommitType.IndexTypes,
    )?.takeIf { it <= snapshotIndex }
    if (nextIndexAnchor != null) {
        return readHistoryChunk(
            fromInclusive = nextIndexAnchor,
            snapshotIndex = snapshotIndex,
        )
    }
    val nextOpenWork = indexesIn(
        range = (afterExclusive + 1)..snapshotIndex,
        types = AgentCommitType.WorkTypes,
    ).take(maxOpenItems)
    val lastOpenIndex = nextOpenWork.lastOrNull() ?: return null
    return readHistoryChunk(
        fromInclusive = lastOpenIndex,
        snapshotIndex = snapshotIndex,
        maxOpenItems = nextOpenWork.size,
    )
}

/** Returns the newest visible stable event index at or before [upperInclusive]. */
internal suspend fun KodexAgentStorage.visibleStableIndexAtOrBefore(
    upperInclusive: Int,
): Int? {
    if (upperInclusive < 0) return null
    return floorToIndex(upperInclusive, AgentCommitType.CleanEventTypes)
}

internal suspend fun KodexAgentStorage.elapsedBetween(
    previousIndex: Int?,
    currentIndex: Int,
): Duration {
    val currentTimestamp = getTimestampExact(currentIndex) ?: return Duration.ZERO
    val previousTimestamp = previousIndex?.let { getTimestampExact(it) }
        ?: return Duration.ZERO
    return (currentTimestamp - previousTimestamp)
        .takeIf { elapsed -> elapsed >= Duration.ZERO && elapsed.isFinite() }
        ?: Duration.ZERO
}

/**
 * Computes final-message footer durations and the current turn start without scanning work
 * payloads. Each query stops at the preceding final assistant message.
 */
internal class HistoryTurnDurationResolver(
    private val storage: KodexAgentStorage,
) {
    suspend fun finalDuration(finalIndex: Int): Duration? {
        if (finalIndex <= 0) return null
        val turnStartIndex = findTurnStartIndex(finalIndex - 1) ?: return null
        return storage.elapsedBetween(turnStartIndex, finalIndex)
    }

    suspend fun activeTurnStartTimestamp(atIndex: Int): Instant? {
        if (atIndex < 0) return null
        val turnStartIndex = findTurnStartIndex(atIndex) ?: return null
        return storage.getTimestampExact(turnStartIndex)
    }

    private suspend fun findTurnStartIndex(upperInclusive: Int): Int? {
        if (upperInclusive < 0) return null
        val prevFinalIndex = storage.floorToIndex(
            index = upperInclusive,
            types = setOf(AgentCommitType.AssistantMessage, AgentCommitType.AssistantMessageFinal),
        )
        val lowerBound = (prevFinalIndex ?: -1) + 1
        if (lowerBound > upperInclusive) return null
        return storage.getFirstCommitsIn(
            range = lowerBound..upperInclusive,
            types = setOf(AgentCommitType.UserMessage),
            limit = 1,
        ).firstOrNull()?.index
    }
}

internal fun StableAssistantMessage.isFinal(): Boolean =
    phase != MessagePhase.Commentary

private data class IndexedEntry(
    val index: Int,
    val entry: CleanIndexEntry,
) {
    fun normalWorkLowerBound(): Int =
        index + if (entry is CleanCompactionPoint && index > 0) 2 else 1

    fun cursorIndex(representation: AnchorRepresentation?): Int =
        representation?.displayIndex ?: index

    suspend fun previous(storage: KodexAgentStorage): IndexedEntry? {
        if (index == 0) return null
        val previousIndex = storage.floorToIndex(index - 1, AgentCommitType.IndexTypes) ?: return null
        val entry = storage.getIndexEntryExact(previousIndex) ?: return null
        return IndexedEntry(
            index = previousIndex,
            entry = entry,
        )
    }

    suspend fun representationAt(
        storage: KodexAgentStorage,
        upperInclusive: Int,
    ): AnchorRepresentation? = when (val value = entry) {
        is StableIndexEvent -> AnchorRepresentation.IndexEvent(index, value)
        is CleanCompactionPoint -> {
            val outputIndex = index + 1
            if (
                index > 0 &&
                outputIndex <= upperInclusive &&
                storage.indexesIn(outputIndex..outputIndex, AgentCommitType.WorkTypes).singleOrNull() == outputIndex
            ) {
                AnchorRepresentation.ContextCompaction(outputIndex)
            } else {
                null
            }
        }
    }
}

private sealed interface AnchorRepresentation {
    val displayIndex: Int

    suspend fun toProjection(
        storage: KodexAgentStorage,
        predecessorIndex: Int?,
    ): HistoryProjectionItem

    data class IndexEvent(
        override val displayIndex: Int,
        val event: StableIndexEvent,
    ) : AnchorRepresentation {
        override suspend fun toProjection(
            storage: KodexAgentStorage,
            predecessorIndex: Int?,
        ): HistoryProjectionItem = HistoryProjectionItem.Stable(
            event.toHistoryItemDescriptor(
                index = displayIndex,
                source = HistoryItemSource.Index,
                elapsed = storage.elapsedBetween(predecessorIndex, displayIndex),
            ),
        )
    }

    data class ContextCompaction(
        override val displayIndex: Int,
    ) : AnchorRepresentation {
        override suspend fun toProjection(
            storage: KodexAgentStorage,
            predecessorIndex: Int?,
        ): HistoryProjectionItem = HistoryProjectionItem.Stable(
            HistoryItemDescriptor(
                index = displayIndex,
                source = HistoryItemSource.Work,
                kind = HistoryItemKind.ContextCompaction,
                elapsed = storage.elapsedBetween(predecessorIndex, displayIndex),
            ),
        )
    }
}

private suspend fun KodexAgentStorage.sealedWorkIndexes(
    older: IndexedEntry?,
    newerIndex: Int,
): List<Int> {
    val firstIndex = (older?.index ?: -1) + 1
    val lastIndex = newerIndex - 1
    if (firstIndex > lastIndex) return emptyList()
    val excludedContextOutput = older
        ?.takeIf { it.entry is CleanCompactionPoint && it.index > 0 }
        ?.let { it.index + 1 }
    return indexesIn(firstIndex..lastIndex, AgentCommitType.WorkTypes).filter { workIndex ->
        workIndex != excludedContextOutput
    }
}

private suspend fun KodexAgentStorage.individualWorkItems(
    commitsNewestFirst: List<IndexedAgentCommit>,
    oldestPredecessorIndex: Int?,
): List<HistoryProjectionItem> {
    if (commitsNewestFirst.isEmpty()) return emptyList()
    val timestamps = if (commitsNewestFirst.size > 1) {
        getCommitsIn(
            (oldestPredecessorIndex ?: commitsNewestFirst.last().index)..commitsNewestFirst.first().index,
            AgentCommitType.TimestampTypes,
        ).associate { (index, value) -> index to (value as CommitTimestamp).instant }
    } else null
    return commitsNewestFirst.mapIndexed { position, (index, commit) ->
        val event = commit as StableWorkEvent
        val predecessorIndex = commitsNewestFirst.getOrNull(position + 1)?.index
            ?: oldestPredecessorIndex
        HistoryProjectionItem.Stable(
            event.toHistoryItemDescriptor(
                index = index,
                source = HistoryItemSource.Work,
                elapsed = if (timestamps == null) elapsedBetween(predecessorIndex, index) else {
                    val current = timestamps[index]
                    val previous = timestamps[predecessorIndex]
                    if (current == null || previous == null) Duration.ZERO
                    else (current - previous).takeIf { it >= Duration.ZERO && it.isFinite() } ?: Duration.ZERO
                },
            ),
        )
    }
}
