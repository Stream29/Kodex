package io.github.stream29.codex.lite.agentstorage.inmemory

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentRawDataStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.codex.lite.utils.SafeRw
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * Process-local mutable raw storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. It follows the
 * storage contract directly: every timeline is a sparse step-function
 * timeline.
 */
public class InMemoryCodexAgentRawDataStorage : MutableCodexAgentRawDataStorage {
    public override val history: MutableIndexVersioned<ResponseItem> = InMemoryIndexVersioned()
    public override val compaction: MutableIndexVersioned<CompactionCheckpoint> = InMemoryIndexVersioned()
    public override val settings: MutableIndexVersioned<CodexAgentSettings> = InMemoryIndexVersioned()
}

private class InMemoryIndexVersioned<T> : MutableIndexVersioned<T> {
    private val entries = SafeRw<List<IndexedValue<T>>, MutableList<IndexedValue<T>>>(mutableListOf())

    override suspend fun latestIndex(): Int =
        entries.readSession { it.lastOrNull()?.index ?: -1 }

    override suspend fun get(index: Int): T =
        entries.readSession { entries ->
            require(index >= 0) { "Index $index must be non-negative." }
            val entryIndex = entries.floorEntryIndex(index)
            require(entryIndex >= 0) { "No value is visible at index $index." }
            entries[entryIndex].value
        }

    override suspend fun indexes(from: Int): Flow<Int> {
        require(from >= 0) { "Index lower bound $from must be non-negative." }
        val snapshot = entries.readSession { entries ->
            entries.asSequence()
                .map { it.index }
                .filter { it >= from }
                .toList()
        }
        return snapshot.asFlow()
    }

    override suspend fun set(index: Int, value: T) {
        require(index >= 0) { "Index $index must be non-negative." }
        entries.writeSession { entries ->
            val latestIndex = entries.lastOrNull()?.index ?: -1
            require(index > latestIndex) {
                "Sparse append-only timeline requires index greater than $latestIndex, got $index."
            }
            entries += IndexedValue(index, value)
        }
    }

    private fun List<IndexedValue<T>>.floorEntryIndex(index: Int): Int {
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
}
