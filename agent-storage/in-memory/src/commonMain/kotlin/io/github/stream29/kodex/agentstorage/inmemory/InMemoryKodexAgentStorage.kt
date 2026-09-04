package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.utils.ReadWriteMutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. Construction
 * publishes the required snapshot-zero settings, so it can always back a legal
 * empty agent state without a synthetic compaction point.
 */
@OptIn(ExperimentalUuidApi::class)
public class InMemoryKodexAgentStorage private constructor(
    initialIndex: MutableList<IndexedValue<CleanIndexEntry>>,
    initialSettings: MutableList<IndexedValue<KodexAgentSettings>>,
    initialTimestamp: MutableList<IndexedValue<Instant>>,
    initialTokenCount: MutableList<IndexedValue<Long>>,
) : MutableKodexAgentStorage {
    private val identity: Any = Any()

    private constructor(initialState: InitialStorageState) : this(
        initialIndex = initialState.index,
        initialSettings = initialState.settings,
        initialTimestamp = initialState.timestamp,
        initialTokenCount = initialState.tokenCount,
    )

    public constructor(initialSettings: KodexAgentSettings) : this(
        initialStorageState(initialSettings),
    )

    public override val uri: String = "memory:${identity.hashCode().toUInt().toString(16)}"
    public override val index: MutableIndexVersioned<CleanIndexEntry> =
        InMemoryIndexVersioned(initialIndex)
    public override val work: MutableIndexVersioned<StableWorkEvent> =
        InMemoryIndexVersioned()
    public override val settings: MutableIndexVersioned<KodexAgentSettings> =
        InMemoryIndexVersioned(initialSettings)
    public override val timestamp: MutableIndexVersioned<Instant> =
        InMemoryIndexVersioned(initialTimestamp)
    public override val tokenCount: MutableIndexVersioned<Long> =
        InMemoryIndexVersioned(initialTokenCount)
    public override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>> =
        InMemoryIndexVersioned()

    public companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public fun empty(): InMemoryKodexAgentStorage =
            InMemoryKodexAgentStorage(
                initialIndex = mutableListOf(),
                initialSettings = mutableListOf(),
                initialTimestamp = mutableListOf(),
                initialTokenCount = mutableListOf(),
            )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun initialStorageState(
    initialSettings: KodexAgentSettings,
): InitialStorageState {
    val windowId = Uuid.generateV7().toString()
    return InitialStorageState(
        index = mutableListOf(),
        settings = mutableListOf(
            IndexedValue(
                0,
                initialSettings.withGeneratedTurnId().copy(
                    windowNumber = 0,
                    firstWindowId = windowId,
                    previousWindowId = null,
                    windowId = windowId,
                ),
            ),
        ),
        timestamp = initializedTimestamps(),
        tokenCount = mutableListOf(IndexedValue(0, 0L)),
    )
}

private data class InitialStorageState(
    val index: MutableList<IndexedValue<CleanIndexEntry>>,
    val settings: MutableList<IndexedValue<KodexAgentSettings>>,
    val timestamp: MutableList<IndexedValue<Instant>>,
    val tokenCount: MutableList<IndexedValue<Long>>,
)

@OptIn(ExperimentalUuidApi::class)
private fun KodexAgentSettings.withGeneratedTurnId(): KodexAgentSettings =
    if (turnId.isNotEmpty()) this else copy(turnId = Uuid.generateV7().toString())

private fun initializedTimestamps(): MutableList<IndexedValue<Instant>> {
    val timestamp = Clock.System.now()
    return mutableListOf(
        IndexedValue(0, timestamp),
    )
}

private class InMemoryIndexVersioned<T>(
    private val entries: MutableList<IndexedValue<T>> = mutableListOf(),
) : MutableIndexVersioned<T> {
    private val mutex = ReadWriteMutex()

    override suspend fun latestIndex(): Int = mutex.reader.withLock {
        entries.lastOrNull()?.index ?: -1
    }

    override suspend fun get(index: Int): T = mutex.reader.withLock {
        require(index >= 0) { "Index $index must be non-negative." }
        val entryIndex = entries.floorEntryIndex(index)
        require(entryIndex >= 0) { "No value is visible at index $index." }
        entries[entryIndex].value
    }

    override suspend fun getExact(index: Int): T? = mutex.reader.withLock {
        require(index >= 0) { "Index $index must be non-negative." }
        val entryIndex = entries.exactEntryIndex(index)
        entries.getOrNull(entryIndex)?.value
    }

    override suspend fun floorToIndex(index: Int): Int? = mutex.reader.withLock {
        val entryIndex = entries.floorEntryIndex(index)
        if (entryIndex >= 0) entries[entryIndex].index else null
    }

    override suspend fun ceilToIndex(index: Int): Int? = mutex.reader.withLock {
        val entryIndex = entries.ceilingEntryIndex(index)
        if (entryIndex >= 0) entries[entryIndex].index else null
    }

    override suspend fun indexesIn(range: IntRange): List<Int> {
        if (range.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.reader.withLock {
            val first = entries.ceilingEntryIndex(range.first)
            if (first < 0) return@withLock emptyList()
            val lastExclusive = entries.ceilingEntryIndexAfter(range.last)
            val end = if (lastExclusive < 0) entries.size else lastExclusive
            entries.subList(first, end).map(IndexedValue<T>::index)
        }
    }

    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> {
        if (range.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.reader.withLock {
            val first = entries.ceilingEntryIndex(range.first)
            if (first < 0) return@withLock emptyList()
            val lastExclusive = entries.ceilingEntryIndexAfter(range.last)
            val end = if (lastExclusive < 0) entries.size else lastExclusive
            entries.subList(first, end).map { entry -> entry.index to entry.value }
        }
    }

    override suspend fun set(index: Int, value: T) {
        require(index >= 0) { "Index $index must be non-negative." }
        mutex.writer.withLock {
            val latestIndex = entries.lastOrNull()?.index ?: -1
            require(index > latestIndex) {
                "Sparse append-only timeline requires index greater than $latestIndex, got $index."
            }
            entries += IndexedValue(index, value)
        }
    }

    override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) {
            "Revert boundary $untilExclusive must be non-negative."
        }
        mutex.writer.withLock {
            val suffixStart = entries.ceilingEntryIndex(untilExclusive)
            if (suffixStart >= 0) {
                entries.subList(suffixStart, entries.size).clear()
            }
        }
    }
}

private fun <T> List<IndexedValue<T>>.floorEntryIndex(index: Int): Int {
    var low = 0
    var high = lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle].index <= index) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}

private fun <T> List<IndexedValue<T>>.ceilingEntryIndex(index: Int): Int {
    var low = 0
    var high = lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle].index >= index) {
            result = middle
            high = middle - 1
        } else {
            low = middle + 1
        }
    }
    return result
}

private fun <T> List<IndexedValue<T>>.ceilingEntryIndexAfter(index: Int): Int {
    if (index == Int.MAX_VALUE) return -1
    return ceilingEntryIndex(index + 1)
}

private fun <T> List<IndexedValue<T>>.exactEntryIndex(index: Int): Int {
    val position = binarySearchBy(index) { entry -> entry.index }
    return position.takeIf { it >= 0 } ?: -1
}
