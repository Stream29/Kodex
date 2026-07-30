package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.time.Instant

/**
 * Appends a compaction checkpoint, model-visible raw marker, and stable clean
 * marker at one shared storage index.
 *
 * @param prefix Model-visible prefix after compaction.
 * @param marker History marker recorded at the compaction boundary.
 * @param timestamp Timestamp associated with the storage transition.
 * @param tokenCount Nullable because OpenAI may not report a token count for
 * a compaction response; `null` means no token-count timeline entry is written.
 * @param previousCheckpoint Checkpoint whose context-window lineage is advanced.
 * @param nextWindowId Fresh UUIDv7 identifier for the new context window.
 * @param settings Settings active after the compaction transition.
 */
public suspend fun MutableCodexAgentStorage.appendCompactionCheckpoint(
    prefix: List<ResponseItem.HistoryItem>,
    marker: ResponseItem.ContextCompaction,
    timestamp: Instant,
    tokenCount: Long?,
    previousCheckpoint: CompactionCheckpoint,
    nextWindowId: String,
    settings: CodexAgentSettings,
): Int {
    val index = latestIndex() + 1
    return compaction.setWithTransaction(index, CompactionCheckpoint(
        prefix = prefix,
        historyBaseIndex = index + 1,
        windowNumber = previousCheckpoint.windowNumber + 1,
        firstWindowId = previousCheckpoint.firstWindowId,
        previousWindowId = previousCheckpoint.windowId,
        windowId = nextWindowId,
    )) {
        this.settings.setWithTransaction(index, settings) {
            history.setWithTransaction(index, marker) {
                stable.setWithTransaction(index, StableContextCompaction) {
                    if (tokenCount == null) {
                        this.timestamp.setWithTransaction(index, timestamp) { index }
                    } else {
                        this.tokenCount.setWithTransaction(index, tokenCount) {
                            this.timestamp.setWithTransaction(index, timestamp) { index }
                        }
                    }
                }
            }
        }
    }
}
