package io.github.stream29.codex.lite.agentstorage.inmemory

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.codex.lite.utils.SafeRw
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. Construction
 * publishes the required snapshot-zero settings, checkpoint, and empty plan,
 * so it can always back a legal empty agent state.
 */
@OptIn(ExperimentalUuidApi::class)
public class InMemoryCodexAgentStorage(
    initialSettings: CodexAgentSettings,
) : MutableCodexAgentStorage {
    private val initialWindowId: String = Uuid.generateV7().toString()

    public override val id: String = Uuid.generateV7().toString()
    public override val history: MutableIndexVersioned<ResponseItem.HistoryItem> = InMemoryIndexVersioned()
    public override val compaction: MutableIndexVersioned<CompactionCheckpoint> =
        InMemoryIndexVersioned(
            listOf(
                IndexedValue(
                    0,
                    CompactionCheckpoint(
                        prefix = emptyList(),
                        historyBaseIndex = 0,
                        windowNumber = 0,
                        firstWindowId = initialWindowId,
                        windowId = initialWindowId,
                    ),
                ),
            ),
        )
    public override val settings: MutableIndexVersioned<CodexAgentSettings> =
        InMemoryIndexVersioned(listOf(IndexedValue(0, initialSettings)))
    public override val plan: MutableIndexVersioned<UpdatePlanArgs> =
        InMemoryIndexVersioned(listOf(IndexedValue(0, UpdatePlanArgs(plan = emptyList()))))
    public override val timestamp: MutableIndexVersioned<Instant> = InMemoryIndexVersioned()
    public override val tokenCount: MutableIndexVersioned<Long> = InMemoryIndexVersioned()
}

private class InMemoryIndexVersioned<T>(
    initialEntries: List<IndexedValue<T>> = emptyList(),
) : MutableIndexVersioned<T> {
    private val entries = SafeRw<List<IndexedValue<T>>, MutableList<IndexedValue<T>>>(initialEntries.toMutableList())

    override suspend fun latestIndex(): Int =
        entries.readSession { it.lastOrNull()?.index ?: -1 }

    override suspend fun get(index: Int): T =
        entries.readSession { entries ->
            require(index >= 0) { "Index $index must be non-negative." }
            val entryIndex = entries.floorEntryIndex(index)
            require(entryIndex >= 0) { "No value is visible at index $index." }
            entries[entryIndex].value
        }

    override suspend fun floorToIndex(index: Int): Int? {
        return entries.readSession { entries ->
            val entryIndex = entries.floorEntryIndex(index)
            if (entryIndex >= 0) entries[entryIndex].index else null
        }
    }

    override suspend fun ceilToIndex(index: Int): Int? {
        return entries.readSession { entries ->
            val entryIndex = entries.ceilingEntryIndex(index)
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

    override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) {
            "Revert boundary $untilExclusive must be non-negative."
        }
        entries.writeSession { entries ->
            val suffixStart = entries.ceilingEntryIndex(untilExclusive)
            if (suffixStart >= 0) {
                entries.subList(suffixStart, entries.size).clear()
            }
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
