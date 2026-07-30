package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.time.Instant

/**
 * Appends a clean compaction checkpoint and stable boundary marker at one
 * shared storage index.
 *
 * @param prefix Stable clean prefix retained after compaction.
 * @param compaction Provider compaction payload retained by the checkpoint.
 * @param timestamp Timestamp associated with the storage transition.
 * @param tokenCount Nullable because OpenAI may not report a token count for
 * a compaction response; `null` means no token-count timeline entry is written.
 * @param previousCheckpoint Checkpoint whose context-window lineage is advanced.
 * @param nextWindowId Fresh UUIDv7 identifier for the new context window.
 * @param settings Settings active after the compaction transition.
 */
public suspend fun MutableCodexAgentStorage.appendCompactionCheckpoint(
    prefix: List<StableCleanEvent>,
    compaction: ResponseItem.Compaction?,
    timestamp: Instant,
    tokenCount: Long?,
    previousCheckpoint: CleanCompactionCheckpoint,
    nextWindowId: String,
    settings: CodexAgentSettings,
): Int {
    val index = latestIndex() + 1
    return this.compaction.setWithTransaction(index, CleanCompactionCheckpoint(
        prefix = prefix,
        compaction = compaction,
        historyBaseIndex = index + 1,
        windowNumber = previousCheckpoint.windowNumber + 1,
        firstWindowId = previousCheckpoint.firstWindowId,
        previousWindowId = previousCheckpoint.windowId,
        windowId = nextWindowId,
    )) {
        this.settings.setWithTransaction(index, settings) {
            stable.setWithTransaction(index, StableCleanEvent.ContextCompaction) {
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
