package io.github.stream29.codex.lite.agentstorage.inmemory

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.codex.lite.utils.SafeRw
import kotlin.time.Instant

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. It follows the
 * storage contract directly: every timeline is a sparse step-function
 * timeline.
 */
public class InMemoryCodexAgentStorage : MutableCodexAgentStorage {
    public override val history: MutableIndexVersioned<ResponseItem> = InMemoryIndexVersioned()
    public override val compaction: MutableIndexVersioned<CompactionCheckpoint> = InMemoryIndexVersioned()
    public override val settings: MutableIndexVersioned<CodexAgentSettings> = InMemoryIndexVersioned()
    public override val plan: MutableIndexVersioned<UpdatePlanArgs> = InMemoryIndexVersioned()
    public override val timestamp: MutableIndexVersioned<Instant> = InMemoryIndexVersioned()
    public override val tokenCount: MutableIndexVersioned<Long> = InMemoryIndexVersioned()
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

    override suspend fun nextIndex(from: Int): Int? {
        require(from >= 0) { "Index lower bound $from must be non-negative." }
        return entries.readSession { entries ->
            val entryIndex = entries.ceilingEntryIndex(from)
            if (entryIndex >= 0) entries[entryIndex].index else null
        }
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

    private fun List<IndexedValue<T>>.ceilingEntryIndex(index: Int): Int {
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
}
