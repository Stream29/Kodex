package io.github.stream29.codex.lite.cli.history

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.prevIndex

/**
 * Finite, newest-first window of committed clean history and the current
 * unfinished clean-event tail.
 *
 * [generation] changes after a destructive history replacement. Ordinary
 * appends and older-window loads retain it so Compose keys remain stable.
 */
public data class AgentHistoryWindow(
    public val generation: Long = 0,
    public val entries: List<AgentHistoryStoredEntry> = emptyList(),
    /** Ordered current unfinished events at the captured storage snapshot. */
    public val pending: List<UnstableCleanEvent> = emptyList(),
    public val hasOlderEntries: Boolean = false,
    public val isLoading: Boolean = true,
    public val failureMessage: String? = null,
)

/** One committed clean event addressed by its real sparse storage index. */
public data class AgentHistoryStoredEntry(
    public val index: Int,
    public val event: StableCleanEvent,
)

internal data class LoadedHistoryBatch(
    val entries: List<AgentHistoryStoredEntry>,
    val nextOlderIndex: Int?,
)

/**
 * Reads at most [limit] completed clean events at or before [fromInclusive].
 */
internal suspend fun loadHistoryBatch(
    storage: CodexAgentStorage,
    fromInclusive: Int,
    limit: Int,
): LoadedHistoryBatch {
    require(limit > 0) { "History batch size must be positive." }
    if (fromInclusive < 0) return LoadedHistoryBatch(emptyList(), null)

    val entries = ArrayList<AgentHistoryStoredEntry>(limit)
    var index = storage.stable.floorToIndex(fromInclusive)
    while (index != null && entries.size < limit) {
        entries += AgentHistoryStoredEntry(index, storage.stable[index])
        index = storage.stable.prevIndex(index)
    }
    return LoadedHistoryBatch(entries, index)
}

/** Reads the unfinished-event snapshot visible at [snapshotIndex]. */
internal suspend fun loadPendingTail(
    storage: CodexAgentStorage,
    snapshotIndex: Int,
): List<UnstableCleanEvent> {
    if (snapshotIndex < 0 || storage.unstable.floorToIndex(snapshotIndex) == null) {
        return emptyList()
    }
    return storage.unstable[snapshotIndex]
}
