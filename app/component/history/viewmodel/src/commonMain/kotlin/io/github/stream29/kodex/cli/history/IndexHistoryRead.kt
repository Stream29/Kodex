package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrAfter
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
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

    val current = getCommitAtOrBefore(fromInclusive, AgentCommitType.IndexTypes)?.toIndexedEntry()
    val openLowerBound = current?.normalWorkLowerBound() ?: 0
    val openWorkCommits = if (fromInclusive >= openLowerBound) {
        getLastCommitsIn(
            range = openLowerBound..fromInclusive,
            types = AgentCommitType.WorkTypes,
            limit = maxOpenItems + 1,
        )
    } else {
        emptyList()
    }
    if (openWorkCommits.isNotEmpty()) {
        val hasMoreOpenWork = openWorkCommits.size > maxOpenItems
        val predecessorCommit = if (hasMoreOpenWork) openWorkCommits.first() else null
        val selectedCommits = (if (hasMoreOpenWork) openWorkCommits.drop(1) else openWorkCommits).asReversed()
        val predecessorIndex = predecessorCommit?.index ?: current?.index
        val predecessorTimestamp = predecessorCommit?.createdAt ?: current?.createdAt
        return LoadedHistoryChunk(
            items = individualWorkItems(
                commitsNewestFirst = selectedCommits,
                oldestPredecessorTimestamp = predecessorTimestamp,
            ),
            nextOlderIndex = predecessorIndex,
        )
    }

    if (current == null) return LoadedHistoryChunk(emptyList(), null)
    val older = current.previous(this)
    val result = ArrayList<HistoryProjectionItem>(2)
    projectAnchoredInterval(
        current = current,
        older = older,
        anchorsAscending = listOfNotNull(older, current),
        anchorPosition = if (older != null) 1 else 0,
        turnDurationResolver = HistoryTurnDurationResolver(this),
        destination = result,
    )
    return LoadedHistoryChunk(
        items = result,
        nextOlderIndex = older?.index,
    )
}

/**
 * Reads a full backward page at or before [fromInclusive] by batching index anchors in a single
 * query and reusing the in-memory anchor sequence across consecutive intervals and turn-duration
 * lookbacks.
 */
internal suspend fun KodexAgentStorage.readHistoryPage(
    fromInclusive: Int,
    snapshotIndex: Int,
    size: Int = HistoryPaging.PageSize,
    newerThan: Int? = null,
): LoadedHistoryChunk {
    require(size > 0)
    if (fromInclusive < 0 || (newerThan != null && fromInclusive <= newerThan)) {
        return LoadedHistoryChunk(emptyList(), null)
    }
    require(snapshotIndex >= fromInclusive) {
        "History snapshot $snapshotIndex cannot precede cursor $fromInclusive."
    }

    val items = ArrayList<HistoryProjectionItem>(size + 1)
    var cursor: Int = fromInclusive
    val lowerBound = (newerThan ?: -1) + 1
    val fetchedAnchors = if (cursor >= lowerBound) {
        getLastCommitsIn(
            range = lowerBound..cursor,
            types = AgentCommitType.IndexTypes,
            limit = size + 1,
        ).mapNotNull(IndexedAgentCommit::toIndexedEntry)
    } else {
        emptyList()
    }
    val predecessorBeforeRange = when {
        fetchedAnchors.isEmpty() ->
            if (lowerBound > 0) getCommitAtOrBefore(lowerBound - 1, AgentCommitType.IndexTypes)?.toIndexedEntry() else null
        fetchedAnchors.size <= size && fetchedAnchors.first().index > 0 ->
            getCommitAtOrBefore(fetchedAnchors.first().index - 1, AgentCommitType.IndexTypes)?.toIndexedEntry()
        else -> null
    }
    val anchorsAscending = buildList(fetchedAnchors.size + 1) {
        if (predecessorBeforeRange != null) add(predecessorBeforeRange)
        addAll(fetchedAnchors)
    }

    val latestAnchor = anchorsAscending.lastOrNull()
    val openLowerBound = latestAnchor?.normalWorkLowerBound() ?: 0
    val openStart = maxOf(openLowerBound, lowerBound)
    if (cursor >= openStart) {
        val maxOpenItems = if (newerThan == null) size else minOf(size, cursor - newerThan)
        val openWorkCommits = getLastCommitsIn(
            range = openLowerBound..cursor,
            types = AgentCommitType.WorkTypes,
            limit = maxOpenItems + 1,
        )
        if (openWorkCommits.isNotEmpty()) {
            val hasMoreOpenWork = openWorkCommits.size > maxOpenItems
            val predecessorCommit = if (hasMoreOpenWork) openWorkCommits.first() else null
            val selectedCommits = (if (hasMoreOpenWork) openWorkCommits.drop(1) else openWorkCommits).asReversed()
            val predecessorIndex = predecessorCommit?.index ?: latestAnchor?.index
            val predecessorTimestamp = predecessorCommit?.createdAt ?: latestAnchor?.createdAt
            items += individualWorkItems(
                commitsNewestFirst = selectedCommits,
                oldestPredecessorTimestamp = predecessorTimestamp,
            )
            val nextOlder = predecessorIndex
            if (items.size >= size || nextOlder == null || (newerThan != null && nextOlder <= newerThan)) {
                return LoadedHistoryChunk(items, nextOlder)
            }
            cursor = nextOlder
        }
    }

    val turnDurationResolver = HistoryTurnDurationResolver(this)
    var position = anchorsAscending.indexOfLast { it.index <= cursor }
    val minPosition = if (predecessorBeforeRange != null) 1 else 0
    while (position >= minPosition && items.size < size) {
        val current = anchorsAscending[position]
        if (newerThan != null && current.index <= newerThan) break
        val older = if (position > 0) anchorsAscending[position - 1] else null
        projectAnchoredInterval(
            current = current,
            older = older,
            anchorsAscending = anchorsAscending,
            anchorPosition = position,
            turnDurationResolver = turnDurationResolver,
            destination = items,
        )
        if (items.size >= size) {
            return LoadedHistoryChunk(items, older?.index)
        }
        position -= 1
    }
    val nextOlder = if (position >= minPosition) {
        anchorsAscending[position].index
    } else {
        predecessorBeforeRange?.index
    }
    return LoadedHistoryChunk(items, nextOlder)
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
    val nextIndexAnchor = getCommitAtOrAfter(
        index = afterExclusive + 1,
        types = AgentCommitType.IndexTypes,
    )?.index?.takeIf { it <= snapshotIndex }
    if (nextIndexAnchor != null) {
        return readHistoryChunk(
            fromInclusive = nextIndexAnchor,
            snapshotIndex = snapshotIndex,
        )
    }
    val current = getCommitAtOrBefore(afterExclusive, AgentCommitType.IndexTypes)?.toIndexedEntry()
    return readNewerOpenWorkChunk(
        afterExclusive = afterExclusive,
        snapshotIndex = snapshotIndex,
        maxOpenItems = maxOpenItems,
        current = current,
    )
}

/**
 * Reads a full forward page after [afterExclusive] by batching forward index anchors in a single
 * query and reusing the in-memory anchor sequence across consecutive intervals.
 */
internal suspend fun KodexAgentStorage.readNewerHistoryPage(
    afterExclusive: Int,
    snapshotIndex: Int,
): LoadedHistoryChunk? {
    if (afterExclusive >= snapshotIndex) return null
    val forwardAnchors = getFirstCommitsIn(
        range = (afterExclusive + 1)..snapshotIndex,
        types = AgentCommitType.IndexTypes,
        limit = HistoryPaging.PageSize,
    ).mapNotNull(IndexedAgentCommit::toIndexedEntry)
    val predecessor = if (afterExclusive >= 0) {
        getCommitAtOrBefore(afterExclusive, AgentCommitType.IndexTypes)?.toIndexedEntry()
    } else {
        null
    }

    if (forwardAnchors.isEmpty()) {
        return readNewerOpenWorkChunk(
            afterExclusive = afterExclusive,
            snapshotIndex = snapshotIndex,
            maxOpenItems = HistoryPaging.PageSize,
            current = predecessor,
        )
    }

    val anchorsAscending = buildList(forwardAnchors.size + 1) {
        if (predecessor != null) add(predecessor)
        addAll(forwardAnchors)
    }
    val turnDurationResolver = HistoryTurnDurationResolver(this)
    val chunksChronological = ArrayList<List<HistoryProjectionItem>>(forwardAnchors.size + 1)
    var totalItems = 0
    val startPosition = if (predecessor != null) 1 else 0
    var lastConsumedPosition = startPosition - 1
    for (position in startPosition..anchorsAscending.lastIndex) {
        if (totalItems >= HistoryPaging.PageSize) break
        val current = anchorsAscending[position]
        val older = if (position > 0) anchorsAscending[position - 1] else null
        val pairItems = ArrayList<HistoryProjectionItem>(2)
        projectAnchoredInterval(
            current = current,
            older = older,
            anchorsAscending = anchorsAscending,
            anchorPosition = position,
            turnDurationResolver = turnDurationResolver,
            destination = pairItems,
        )
        chunksChronological += pairItems
        totalItems += pairItems.size
        lastConsumedPosition = position
    }

    if (totalItems < HistoryPaging.PageSize && lastConsumedPosition == anchorsAscending.lastIndex) {
        val lastAnchor = anchorsAscending.last()
        val openChunk = readNewerOpenWorkChunk(
            afterExclusive = lastAnchor.index,
            snapshotIndex = snapshotIndex,
            maxOpenItems = HistoryPaging.PageSize - totalItems,
            current = lastAnchor,
        )
        if (openChunk != null && openChunk.items.isNotEmpty()) {
            chunksChronological += openChunk.items
            totalItems += openChunk.items.size
        }
    }

    if (chunksChronological.isEmpty()) return null
    val combinedItems = ArrayList<HistoryProjectionItem>(totalItems)
    for (index in chunksChronological.lastIndex downTo 0) {
        combinedItems += chunksChronological[index]
    }
    return LoadedHistoryChunk(
        items = combinedItems,
        nextOlderIndex = predecessor?.index,
    )
}

private suspend fun KodexAgentStorage.readNewerOpenWorkChunk(
    afterExclusive: Int,
    snapshotIndex: Int,
    maxOpenItems: Int,
    current: IndexedEntry?,
): LoadedHistoryChunk? {
    val openLowerBound = current?.normalWorkLowerBound() ?: 0
    if (snapshotIndex < openLowerBound) return null
    val (predecessorCommit, selectedCommits) = if (afterExclusive >= openLowerBound) {
        val fetched = getFirstCommitsIn(
            range = afterExclusive..snapshotIndex,
            types = AgentCommitType.WorkTypes,
            limit = maxOpenItems + 1,
        )
        if (fetched.firstOrNull()?.index == afterExclusive) {
            fetched.first() to fetched.drop(1)
        } else {
            val pred = if (afterExclusive > openLowerBound) {
                getCommitAtOrBefore(afterExclusive - 1, AgentCommitType.WorkTypes)
                    ?.takeIf { it.index >= openLowerBound }
            } else {
                null
            }
            pred to fetched.take(maxOpenItems)
        }
    } else {
        null to getFirstCommitsIn(
            range = openLowerBound..snapshotIndex,
            types = AgentCommitType.WorkTypes,
            limit = maxOpenItems,
        )
    }
    if (selectedCommits.isEmpty()) return null
    val predecessorIndex = predecessorCommit?.index ?: current?.index
    val predecessorTimestamp = predecessorCommit?.createdAt ?: current?.createdAt
    return LoadedHistoryChunk(
        items = individualWorkItems(
            commitsNewestFirst = selectedCommits.asReversed(),
            oldestPredecessorTimestamp = predecessorTimestamp,
        ),
        nextOlderIndex = predecessorIndex,
    )
}

private suspend fun KodexAgentStorage.projectAnchoredInterval(
    current: IndexedEntry,
    older: IndexedEntry?,
    anchorsAscending: List<IndexedEntry>,
    anchorPosition: Int,
    turnDurationResolver: HistoryTurnDurationResolver,
    destination: MutableList<HistoryProjectionItem>,
) {
    val sealedWork = sealedWorkSummary(
        older = older,
        newerIndex = current.index,
    )
    val predecessorTimestamp = sealedWork?.lastTimestamp ?: older?.createdAt
    val turnDuration = (current.entry as? StableAssistantMessage)
        ?.takeIf(StableAssistantMessage::isFinal)
        ?.let {
            resolveTurnDurationInMemoryOrStorage(
                anchorsAscending = anchorsAscending,
                finalPosition = anchorPosition,
                turnDurationResolver = turnDurationResolver,
            )
        }
    destination += HistoryProjectionItem.Stable(
        current.entry.toHistoryItemDescriptor(
            index = current.index,
            source = HistoryItemSource.Index,
            elapsed = elapsedBetween(predecessorTimestamp, current.createdAt),
            createdAt = current.createdAt,
            turnDuration = turnDuration,
        ),
    )
    if (sealedWork != null) {
        destination += HistoryProjectionItem.WorkGroup(
            indexRange = sealedWork.indexRange,
            itemCount = sealedWork.itemCount,
            elapsed = elapsedBetween(
                previousTimestamp = older?.createdAt,
                currentTimestamp = sealedWork.lastTimestamp,
            ),
        )
    }
}

private suspend fun resolveTurnDurationInMemoryOrStorage(
    anchorsAscending: List<IndexedEntry>,
    finalPosition: Int,
    turnDurationResolver: HistoryTurnDurationResolver,
): Duration? {
    val finalEntry = anchorsAscending[finalPosition]
    if (finalEntry.index <= 0) return null
    var firstUserInTurn: IndexedEntry? = null
    for (pos in (finalPosition - 1) downTo 0) {
        val entry = anchorsAscending[pos]
        val event = entry.entry
        if (event is StableAssistantMessage && event.isFinal()) {
            return firstUserInTurn?.let { elapsedBetween(it.createdAt, finalEntry.createdAt) }
        }
        if (event is StableUserMessage) {
            firstUserInTurn = entry
        }
    }
    if (anchorsAscending.first().index == 0) {
        return firstUserInTurn?.let { elapsedBetween(it.createdAt, finalEntry.createdAt) }
    }
    return turnDurationResolver.finalDuration(finalEntry.index, finalEntry.createdAt)
}

internal fun elapsedBetween(
    previousTimestamp: Instant?,
    currentTimestamp: Instant?,
): Duration {
    val current = currentTimestamp?.takeIf { it != Instant.DISTANT_PAST } ?: return Duration.ZERO
    val previous = previousTimestamp?.takeIf { it != Instant.DISTANT_PAST } ?: return Duration.ZERO
    return (current - previous)
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
    suspend fun finalDuration(finalIndex: Int, finalCreatedAt: Instant? = null): Duration? {
        if (finalIndex <= 0) return null
        val turnStartCommit = findTurnStartCommit(finalIndex - 1) ?: return null
        val endTimestamp = finalCreatedAt ?: storage.getCommitExact(finalIndex)?.createdAt ?: return null
        return elapsedBetween(turnStartCommit.createdAt, endTimestamp)
    }

    suspend fun activeTurnStartTimestamp(atIndex: Int): Instant? {
        if (atIndex < 0) return null
        return findTurnStartCommit(atIndex)?.createdAt?.takeIf { it != Instant.DISTANT_PAST }
    }

    private suspend fun findTurnStartCommit(upperInclusive: Int): IndexedAgentCommit? {
        if (upperInclusive < 0) return null
        val prevFinalIndex = storage.getLastCommitsIn(
            range = 0..upperInclusive,
            types = setOf(AgentCommitType.AssistantMessage, AgentCommitType.AssistantMessageFinal),
            limit = 1,
        ).lastOrNull()?.index
        val lowerBound = (prevFinalIndex ?: -1) + 1
        if (lowerBound > upperInclusive) return null
        return storage.getFirstCommitsIn(
            range = lowerBound..upperInclusive,
            types = setOf(AgentCommitType.UserMessage),
            limit = 1,
        ).firstOrNull()
    }
}

internal fun StableAssistantMessage.isFinal(): Boolean =
    phase != MessagePhase.Commentary

private data class IndexedEntry(
    val index: Int,
    val entry: CleanIndexEntry,
    val createdAt: Instant,
) {
    fun normalWorkLowerBound(): Int = index + 1

    suspend fun previous(storage: KodexAgentStorage): IndexedEntry? {
        if (index <= 0) return null
        return storage.getCommitAtOrBefore(index - 1, AgentCommitType.IndexTypes)?.toIndexedEntry()
    }
}

private fun IndexedAgentCommit.toIndexedEntry(): IndexedEntry? =
    (commit as? CleanIndexEntry)?.let { IndexedEntry(index, it, createdAt) }

private data class SealedWorkSummary(
    val indexRange: IntRange,
    val itemCount: Int,
    val lastTimestamp: Instant,
)

private suspend fun KodexAgentStorage.sealedWorkSummary(
    older: IndexedEntry?,
    newerIndex: Int,
): SealedWorkSummary? {
    val firstIndex = older?.normalWorkLowerBound() ?: 0
    val lastIndex = newerIndex - 1
    if (firstIndex > lastIndex) return null
    val count = countCommitsIn(firstIndex..lastIndex, AgentCommitType.WorkTypes)
    if (count <= 0) return null
    val firstWork = getFirstCommitsIn(firstIndex..lastIndex, AgentCommitType.WorkTypes, limit = 1).firstOrNull()
        ?: return null
    val lastWork = if (count == 1) {
        firstWork
    } else {
        getLastCommitsIn(firstIndex..lastIndex, AgentCommitType.WorkTypes, limit = 1).lastOrNull() ?: return null
    }
    return SealedWorkSummary(
        indexRange = firstWork.index..lastWork.index,
        itemCount = count,
        lastTimestamp = lastWork.createdAt,
    )
}

private fun individualWorkItems(
    commitsNewestFirst: List<IndexedAgentCommit>,
    oldestPredecessorTimestamp: Instant? = null,
): List<HistoryProjectionItem> {
    if (commitsNewestFirst.isEmpty()) return emptyList()
    return commitsNewestFirst.mapIndexed { position, (index, commit, createdAt) ->
        val event = commit as StableWorkEvent
        val predecessorCommit = commitsNewestFirst.getOrNull(position + 1)
        val predecessorTimestamp = predecessorCommit?.createdAt
            ?.takeIf { it != Instant.DISTANT_PAST }
            ?: oldestPredecessorTimestamp
        HistoryProjectionItem.Stable(
            event.toHistoryItemDescriptor(
                index = index,
                source = HistoryItemSource.Work,
                elapsed = elapsedBetween(predecessorTimestamp, createdAt),
                createdAt = createdAt,
            ),
        )
    }
}
