package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import kotlin.time.Duration

/**
 * One visible entry from the stable timelines.
 *
 * A null [event] is an intentionally opaque work entry. It is used for a
 * collapsed work run so callers can build its UI without decoding its payload.
 */
public data class StableTimelineEntry(
    public val index: Int,
    public val event: StableCleanEvent?,
    public val elapsed: Duration,
    public val groupElapsed: Duration? = null,
) {
    public val isOpaqueWork: Boolean
        get() = event == null
}

/**
 * Finds a lower bound containing at most [maxEntries] visible stable entries.
 *
 * The returned index is exclusive. This operation is deliberately bounded and
 * never enumerates the whole history just to prepare an initial page.
 */
public suspend fun KodexAgentStorage.stableTimelineLowerExclusive(
    upperInclusive: Int,
    maxEntries: Int,
): Int {
    require(maxEntries > 0) { "maxEntries must be positive." }
    if (upperInclusive < 0) return -1

    var cursor = upperInclusive
    var oldestIndex: Int? = null
    var count = 0
    while (cursor >= 0 && count < maxEntries) {
        val candidate = stableTimelineIndexAtOrBefore(cursor) ?: break
        oldestIndex = candidate
        count += 1
        cursor = candidate - 1
    }
    return oldestIndex?.let { it - 1 } ?: -1
}

/** Returns the newest visible stable event index at or before [index]. */
public suspend fun KodexAgentStorage.stableTimelineIndexAtOrBefore(index: Int): Int? {
    if (index < 0) return null
    return maxOfNotNull(
        latestIndexEventAtOrBefore(index),
        work.floorToIndex(index),
    )
}

/**
 * Reads the stable index/work projection in newest-first order.
 *
 * Index entries are anchors for work ranges. Older multi-entry work ranges are
 * returned as opaque entries; the newest range remains materialized so a newly
 * appended work item is immediately visible. Compaction points are omitted and
 * their following context-compaction output is represented as a work event.
 */
public suspend fun KodexAgentStorage.readStableTimeline(
    upperInclusive: Int,
    lowerExclusive: Int = -1,
): List<StableTimelineEntry> {
    if (upperInclusive < 0 || upperInclusive <= lowerExclusive) return emptyList()
    val firstIndex = (lowerExclusive + 1).coerceAtLeast(0)

    val previousAnchor = if (firstIndex == 0) {
        null
    } else {
        this.index.floorToIndex(firstIndex - 1)?.let { entryIndex ->
            IndexedCleanEntry(
                index = entryIndex,
                entry = requireNotNull(this.index.getExact(entryIndex)) {
                    "Index entry $entryIndex disappeared while reading stable history."
                },
            )
        }
    }
    val anchors = this.index.indexesIn(firstIndex..upperInclusive).map { entryIndex ->
        IndexedCleanEntry(
            index = entryIndex,
            entry = requireNotNull(this.index.getExact(entryIndex)) {
                "Index entry $entryIndex disappeared while reading stable history."
            },
        )
    }
    val workIndexes = this.work.indexesIn(firstIndex..upperInclusive)
    val elapsed = elapsedByIndex(
        previousIndex = previousVisibleIndex(previousAnchor, firstIndex),
        stableIndexes = anchors
            .filter { it.entry is StableIndexEvent }
            .map { it.index },
        workIndexes = workIndexes,
    )
    fun entryAt(
        index: Int,
        event: StableCleanEvent?,
        groupElapsed: Duration? = null,
    ): StableTimelineEntry =
        StableTimelineEntry(
            index = index,
            event = event,
            elapsed = elapsed.getValue(index),
            groupElapsed = groupElapsed,
        )

    suspend fun appendWorkRange(
        result: MutableList<StableTimelineEntry>,
        startInclusive: Int,
        endInclusive: Int,
        excludedIndex: Int?,
        opaque: Boolean,
    ) {
        if (startInclusive > endInclusive || endInclusive < 0) return
        val indexes = workIndexes.filter { workIndex ->
            workIndex in startInclusive..endInclusive && workIndex != excludedIndex
        }
        if (indexes.size <= 1 || !opaque) {
            indexes.asReversed().forEach { workIndex ->
                result += entryAt(
                    workIndex,
                    requireNotNull(work.getExact(workIndex)) {
                        "Work entry $workIndex disappeared while reading stable history."
                    },
                )
            }
        } else {
            val duration = workRangeElapsed(
                oldestIndex = indexes.first(),
                newestIndex = indexes.last(),
            )
            indexes.asReversed().forEach { workIndex ->
                result += entryAt(workIndex, null, groupElapsed = duration)
            }
        }
    }

    suspend fun appendAnchor(
        result: MutableList<StableTimelineEntry>,
        anchor: IndexedCleanEntry,
    ) {
        when (val entry = anchor.entry) {
            is StableIndexEvent -> result += entryAt(anchor.index, entry)
            is CleanCompactionPoint -> {
                if (anchor.index == 0) return
                val outputIndex = anchor.index + 1
                val output = requireNotNull(work.getExact(outputIndex)) {
                    "Compaction point ${anchor.index} has no following context-compaction output."
                }
                require(output is StableContextCompaction) {
                    "Compaction point ${anchor.index} is not followed by context compaction."
                }
                if (outputIndex in firstIndex..upperInclusive) {
                    result += entryAt(outputIndex, output)
                }
            }
        }
    }

    val result = ArrayList<StableTimelineEntry>()
    if (anchors.isEmpty()) {
        appendWorkRange(
            result = result,
            startInclusive = firstIndex,
            endInclusive = upperInclusive,
            excludedIndex = previousAnchor?.compactionOutputIndexOrNull(),
            opaque = false,
        )
        previousAnchor
            ?.takeIf { it.entry is CleanCompactionPoint }
            ?.let { appendAnchor(result, it) }
        return result
    }

    val newestAnchor = anchors.last()
    appendWorkRange(
        result = result,
        startInclusive = newestAnchor.index + 1,
        endInclusive = upperInclusive,
        excludedIndex = newestAnchor.compactionOutputIndexOrNull(),
        opaque = false,
    )
    for (position in anchors.indices.reversed()) {
        val current = anchors[position]
        appendAnchor(result, current)
        val older = anchors.getOrNull(position - 1) ?: previousAnchor
        appendWorkRange(
            result = result,
            startInclusive = maxOf(firstIndex, (older?.index ?: -1) + 1),
            endInclusive = current.index - 1,
            excludedIndex = older?.compactionOutputIndexOrNull(),
            opaque = true,
        )
    }
    previousAnchor
        ?.takeIf { it.entry is CleanCompactionPoint }
        ?.let { appendAnchor(result, it) }
    return result
}

private data class IndexedCleanEntry(
    val index: Int,
    val entry: CleanIndexEntry,
) {
    fun compactionOutputIndexOrNull(): Int? =
        (entry as? CleanCompactionPoint)?.let { index + 1 }.takeIf { index > 0 }
}

private suspend fun KodexAgentStorage.latestIndexEventAtOrBefore(index: Int): Int? {
    var candidate = this.index.floorToIndex(index)
    while (candidate != null) {
        if (this.index.getExact(candidate) is StableIndexEvent) return candidate
        candidate = if (candidate == 0) null else this.index.floorToIndex(candidate - 1)
    }
    return null
}

private suspend fun KodexAgentStorage.previousVisibleIndex(
    previousAnchor: IndexedCleanEntry?,
    firstIndex: Int,
): Int? {
    val indexCandidate = previousAnchor
        ?.takeIf { it.entry is StableIndexEvent }
        ?.index
    val workCandidate = if (firstIndex == 0) null else work.floorToIndex(firstIndex - 1)
    return maxOfNotNull(indexCandidate, workCandidate)
}

private suspend fun KodexAgentStorage.workRangeElapsed(
    oldestIndex: Int,
    newestIndex: Int,
): Duration {
    val previousIndex = stableTimelineIndexAtOrBefore(oldestIndex - 1)
    val previousTimestamp = previousIndex?.let { timestamp.getExact(it) }
    val newestTimestamp = timestamp.getExact(newestIndex)
    return if (previousTimestamp != null && newestTimestamp != null) {
        (newestTimestamp - previousTimestamp)
            .takeIf { it >= Duration.ZERO && it.isFinite() }
            ?: Duration.ZERO
    } else {
        Duration.ZERO
    }
}

private suspend fun KodexAgentStorage.elapsedByIndex(
    previousIndex: Int?,
    stableIndexes: List<Int>,
    workIndexes: List<Int>,
): Map<Int, Duration> {
    val targetIndexes = (stableIndexes + workIndexes).toSet()
    if (targetIndexes.isEmpty()) return emptyMap()

    val timestamp = timestamp
    var stablePosition = 0
    var workPosition = 0
    var previousTimestamp = previousIndex?.let { timestamp.getExact(it) }
    val result = HashMap<Int, Duration>(targetIndexes.size)

    while (stablePosition < stableIndexes.size || workPosition < workIndexes.size) {
        val stableIndex = stableIndexes.getOrNull(stablePosition)
        val workIndex = workIndexes.getOrNull(workPosition)
        val currentIndex = when {
            stableIndex == null -> workIndex
            workIndex == null -> stableIndex
            stableIndex <= workIndex -> stableIndex
            else -> workIndex
        } ?: break
        if (stableIndex == currentIndex) stablePosition += 1
        if (workIndex == currentIndex) workPosition += 1

        val currentTimestamp = timestamp.getExact(currentIndex)
        if (currentIndex in targetIndexes) {
            result[currentIndex] = if (
                previousTimestamp != null && currentTimestamp != null
            ) {
                (currentTimestamp - previousTimestamp)
                    .takeIf { it >= Duration.ZERO && it.isFinite() }
                    ?: Duration.ZERO
            } else {
                Duration.ZERO
            }
        }
        previousTimestamp = currentTimestamp
    }
    return result
}

private fun <T : Comparable<T>> maxOfNotNull(vararg values: T?): T? =
    values.filterNotNull().maxOrNull()
