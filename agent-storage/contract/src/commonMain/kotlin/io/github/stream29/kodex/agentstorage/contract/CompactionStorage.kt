package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Appends one compaction point and its provider output at consecutive indexes.
 *
 * @param output Stable provider compaction payload.
 * @param timestamp Timestamp associated with the storage transition.
 * @param previousPoint Point whose context-window lineage is advanced.
 * @param nextWindowId Fresh UUIDv7 identifier for the new context window.
 * @param settings Settings active after the compaction transition.
 *
 * The point index also stores settings and a synthetic token count of `0`.
 * The following output index stores the context-compaction work event and its
 * exact timestamp.
 */
public suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    previousPoint: CleanCompactionPoint,
    nextWindowId: String,
    settings: KodexAgentSettings,
): Int {
    val pointIndex = latestIndex() + 1
    val outputIndex = pointIndex + 1
    val point = CleanCompactionPoint(
        windowNumber = previousPoint.windowNumber + 1,
        firstWindowId = previousPoint.firstWindowId,
        previousWindowId = previousPoint.windowId,
        windowId = nextWindowId,
    )
    return index.setWithTransaction(pointIndex, point) {
        this.settings.setWithTransaction(pointIndex, settings) {
            this.tokenCount.setWithTransaction(pointIndex, 0L) {
                this.timestamp.setWithTransaction(outputIndex, timestamp) {
                    work.setWithTransaction(outputIndex, output) { outputIndex }
                }
            }
        }
    }
}
