package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.time.Instant

/**
 * Appends a compaction checkpoint and its model-visible history marker at one
 * shared storage index.
 *
 * @param prefix Model-visible prefix after compaction.
 * @param marker History marker recorded at the compaction boundary.
 * @param timestamp Timestamp associated with the storage transition.
 * @param tokenCount Nullable because OpenAI may not report a token count for
 * a compaction response; `null` means no token-count timeline entry is written.
 * @param previousCheckpoint Checkpoint whose context-window lineage is advanced.
 * @param nextWindowId Fresh UUIDv7 identifier for the new context window.
 */
public suspend fun MutableCodexAgentStorage.appendCompactionCheckpoint(
    prefix: List<ResponseItem.HistoryItem>,
    marker: ResponseItem.ContextCompaction,
    timestamp: Instant,
    tokenCount: Long?,
    previousCheckpoint: CompactionCheckpoint,
    nextWindowId: String,
): Int {
    val index = latestIndex() + 1
    compaction[index] = CompactionCheckpoint(
        prefix = prefix,
        historyBaseIndex = index + 1,
        windowNumber = previousCheckpoint.windowNumber + 1,
        firstWindowId = previousCheckpoint.firstWindowId,
        previousWindowId = previousCheckpoint.windowId,
        windowId = nextWindowId,
    )
    history[index] = marker
    if (tokenCount != null) {
        this.tokenCount[index] = tokenCount
    }
    this.timestamp[index] = timestamp
    return index
}
