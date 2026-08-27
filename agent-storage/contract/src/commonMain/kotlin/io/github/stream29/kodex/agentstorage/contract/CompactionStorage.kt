package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.RemoteCompactionV2RetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Appends a clean compaction checkpoint and stable compaction event at one
 * shared storage index.
 *
 * @param prefix Stable clean prefix retained after compaction.
 * @param compaction Stable provider compaction payload.
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
    prefix: List<RemoteCompactionV2RetainedItem>,
    compaction: StableCleanEvent.ContextCompaction,
    timestamp: Instant,
    previousCheckpoint: CleanCompactionCheckpoint,
    nextWindowId: String,
    settings: KodexAgentSettings,
): Int {
    val index = latestIndex() + 1
    return this.compaction.setWithTransaction(index, CleanCompactionCheckpoint(
        prefix = prefix,
        historyBaseIndex = index,
        windowNumber = previousCheckpoint.windowNumber + 1,
        firstWindowId = previousCheckpoint.firstWindowId,
        previousWindowId = previousCheckpoint.windowId,
        windowId = nextWindowId,
    )) {
        this.settings.setWithTransaction(index, settings) {
            stable.setWithTransaction(index, compaction) {
                this.tokenCount.setWithTransaction(index, 0L) {
                    this.timestamp.setWithTransaction(index, timestamp) { index }
                }
            }
        }
    }
}
