package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ResponseItem
import kotlin.time.Instant

/**
 * Appends a clean compaction checkpoint and stable boundary marker at one
 * shared storage index.
 *
 * @param prefix Stable clean prefix retained after compaction.
 * @param compaction Provider compaction payload retained by the checkpoint.
 * @param timestamp Timestamp associated with the storage transition.
 * @param previousCheckpoint Checkpoint whose context-window lineage is advanced.
 * @param nextWindowId Fresh UUIDv7 identifier for the new context window.
 * @param settings Settings active after the compaction transition.
 *
 * Every committed checkpoint writes a synthetic token count of `0` at the same
 * index. This prevents the previous context window's count from remaining
 * active until an ordinary response reports the next count.
 */
public suspend fun MutableKodexAgentStorage.appendCompactionCheckpoint(
    prefix: List<StableCleanEvent>,
    compaction: ResponseItem.Compaction?,
    timestamp: Instant,
    previousCheckpoint: CleanCompactionCheckpoint,
    nextWindowId: String,
    settings: KodexAgentSettings,
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
                this.tokenCount.setWithTransaction(index, 0L) {
                    this.timestamp.setWithTransaction(index, timestamp) { index }
                }
            }
        }
    }
}
