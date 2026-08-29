package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.utils.ReadWriteMutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. Construction
 * publishes the required snapshot-zero settings and compaction point, so it can
 * always back a legal empty agent state.
 */
@OptIn(ExperimentalUuidApi::class)
public class InMemoryKodexAgentStorage private constructor(
    initialIndex: MutableList<IndexedValue<CleanIndexEntry>>,
    initialSettings: MutableList<IndexedValue<KodexAgentSettings>>,
) : MutableKodexAgentStorage {
    private val identity: Any = Any()

    public constructor(initialSettings: KodexAgentSettings) : this(
        initialIndex = initializedIndex(),
        initialSettings = mutableListOf(IndexedValue(0, initialSettings)),
    )

    public override val id: String = "memory:${identity.hashCode().toUInt().toString(16)}"
    public override val index: MutableIndexVersioned<CleanIndexEntry> =
        InMemoryIndexVersioned(initialIndex)
    public override val work: MutableIndexVersioned<StableWorkEvent> =
        InMemoryIndexVersioned()
    public override val settings: MutableIndexVersioned<KodexAgentSettings> =
        InMemoryIndexVersioned(initialSettings)
    public override val timestamp: MutableIndexVersioned<Instant> =
        InMemoryIndexVersioned()
    public override val tokenCount: MutableIndexVersioned<Long> =
        InMemoryIndexVersioned()
    public override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>> =
        InMemoryIndexVersioned()

    public companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public fun empty(): InMemoryKodexAgentStorage =
            InMemoryKodexAgentStorage(
                initialIndex = mutableListOf(),
                initialSettings = mutableListOf(),
            )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun initializedIndex(): MutableList<IndexedValue<CleanIndexEntry>> {
    val windowId = Uuid.generateV7().toString()
    return mutableListOf(
        IndexedValue(
            0,
            CleanCompactionPoint(
                windowNumber = 0,
                firstWindowId = windowId,
                windowId = windowId,
            ),
        ),
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

    override suspend fun floorToIndex(index: Int): Int? = mutex.reader.withLock {
        val entryIndex = entries.floorEntryIndex(index)
        if (entryIndex >= 0) entries[entryIndex].index else null
    }

    override suspend fun ceilToIndex(index: Int): Int? = mutex.reader.withLock {
        val entryIndex = entries.ceilingEntryIndex(index)
        if (entryIndex >= 0) entries[entryIndex].index else null
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
