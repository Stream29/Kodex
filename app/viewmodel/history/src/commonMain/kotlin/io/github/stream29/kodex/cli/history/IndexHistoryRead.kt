package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.indexesDescending
import io.github.stream29.kodex.agentstorage.contract.valuesDescending
import io.github.stream29.kodex.openai.MessagePhase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One first-level History item.
 *
 * The index timeline defines the structure. A sealed work interval is represented by one
 * [WorkGroup] regardless of its work-item count; work payloads are not decoded here.
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
 * One chunk is either one open work item, or one index anchor together with its preceding sealed
 * work group. LazyColumn demand decides how many chunks are read; this function has no page size.
 */
internal suspend fun KodexAgentStorage.readHistoryChunk(
    fromInclusive: Int,
    snapshotIndex: Int = fromInclusive,
): LoadedHistoryChunk {
    if (fromInclusive < 0) return LoadedHistoryChunk(emptyList(), null)
    require(snapshotIndex >= fromInclusive) {
        "History snapshot $snapshotIndex cannot precede cursor $fromInclusive."
    }

    val result = ArrayList<HistoryProjectionItem>(2)
    var current = index.floorToIndex(fromInclusive)?.let { entryIndex ->
        IndexedEntry(
            index = entryIndex,
            entry = requireNotNull(index.getExact(entryIndex)) {
                "Index entry $entryIndex disappeared while reading History."
            },
        )
    }
    var currentRepresentation = current?.representationAt(this, snapshotIndex)

    val openLowerBound = current?.normalWorkLowerBound() ?: 0
    val openIndexes = work.indexesDescending(fromInclusive)
        .takeWhile { workIndex -> workIndex >= openLowerBound }
        .take(2)
        .toList()
    openIndexes.firstOrNull()?.let { openIndex ->
        val predecessorIndex = openIndexes.getOrNull(1)
            ?: currentRepresentation?.displayIndex
        result += individualWorkItems(
            indexesNewestFirst = listOf(openIndex),
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
 * When there is no newer index anchor, the open work suffix remains one item per chunk.
 */
internal suspend fun KodexAgentStorage.readNewerHistoryChunk(
    afterExclusive: Int,
    snapshotIndex: Int,
): LoadedHistoryChunk? {
    if (afterExclusive >= snapshotIndex) return null
    val nextIndexAnchor = index.ceilToIndex(afterExclusive + 1)
        ?.takeIf { it <= snapshotIndex }
    if (nextIndexAnchor != null) {
        return readHistoryChunk(
            fromInclusive = nextIndexAnchor,
            snapshotIndex = snapshotIndex,
        )
    }
    val nextOpenWork = work.ceilToIndex(afterExclusive + 1)
        ?.takeIf { it <= snapshotIndex }
        ?: return null
    return readHistoryChunk(
        fromInclusive = nextOpenWork,
        snapshotIndex = snapshotIndex,
    )
}

/** Returns the newest visible stable event index at or before [upperInclusive]. */
internal suspend fun KodexAgentStorage.visibleStableIndexAtOrBefore(
    upperInclusive: Int,
): Int? {
    if (upperInclusive < 0) return null
    val workCandidate = work.floorToIndex(upperInclusive)
    var indexCandidate = index.floorToIndex(upperInclusive)
    while (indexCandidate != null) {
        if (index.getExact(indexCandidate) is StableIndexEvent) break
        indexCandidate = if (indexCandidate == 0) {
            null
        } else {
            index.floorToIndex(indexCandidate - 1)
        }
    }
    return listOfNotNull(workCandidate, indexCandidate).maxOrNull()
}

internal suspend fun KodexAgentStorage.elapsedBetween(
    previousIndex: Int?,
    currentIndex: Int,
): Duration {
    val currentTimestamp = timestamp.getExact(currentIndex) ?: return Duration.ZERO
    val previousTimestamp = previousIndex?.let { timestamp.getExact(it) }
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
        var turnStartIndex: Int? = null
        if (finalIndex > 0) {
            storage.index.valuesDescending(finalIndex - 1).firstOrNull { (index, entry) ->
                when {
                    entry is StableUserMessage -> {
                        turnStartIndex = index
                        false
                    }

                    entry is StableAssistantMessage && entry.isFinal() -> true
                    else -> false
                }
            }
        }
        return turnStartIndex?.let { startIndex ->
            storage.elapsedBetween(startIndex, finalIndex)
        }
    }

    suspend fun activeTurnStartTimestamp(atIndex: Int): Instant? {
        if (atIndex < 0) return null
        var turnStartIndex: Int? = null
        storage.index.valuesDescending(atIndex).firstOrNull { (index, entry) ->
            when {
                entry is StableUserMessage -> {
                    turnStartIndex = index
                    false
                }

                entry is StableAssistantMessage && entry.isFinal() -> true
                else -> false
            }
        }
        return turnStartIndex?.let { storage.timestamp.getExact(it) }
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
        val previousIndex = storage.index.floorToIndex(index - 1) ?: return null
        return IndexedEntry(
            index = previousIndex,
            entry = requireNotNull(storage.index.getExact(previousIndex)) {
                "Index entry $previousIndex disappeared while reading History."
            },
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
                storage.work.indexesIn(outputIndex..outputIndex).singleOrNull() == outputIndex
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
    return work.indexesIn(firstIndex..lastIndex).filter { workIndex ->
        workIndex != excludedContextOutput
    }
}

private suspend fun KodexAgentStorage.individualWorkItems(
    indexesNewestFirst: List<Int>,
    oldestPredecessorIndex: Int?,
): List<HistoryProjectionItem> {
    if (indexesNewestFirst.isEmpty()) return emptyList()
    val valuesByIndex = work.valuesIn(
        indexesNewestFirst.last()..indexesNewestFirst.first(),
    ).associate { (index, event) -> index to event }
    return indexesNewestFirst.mapIndexed { position, index ->
        val event = requireNotNull(valuesByIndex[index]) {
            "Work entry $index disappeared while reading History."
        }
        val predecessorIndex = indexesNewestFirst.getOrNull(position + 1)
            ?: oldestPredecessorIndex
        HistoryProjectionItem.Stable(
            event.toHistoryItemDescriptor(
                index = index,
                source = HistoryItemSource.Work,
                elapsed = elapsedBetween(predecessorIndex, index),
            ),
        )
    }
}
