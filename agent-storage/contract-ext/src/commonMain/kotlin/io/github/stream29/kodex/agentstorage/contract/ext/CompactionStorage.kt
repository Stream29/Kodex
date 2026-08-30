package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/** Appends one compaction point and its provider output at consecutive indexes. */
public suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    nextWindowId: String,
    previousSettings: KodexAgentSettings,
): Int {
    val pointIndex = latestIndex() + 1
    val outputIndex = pointIndex + 1
    val nextSettings = previousSettings.copy(
        windowNumber = previousSettings.windowNumber + 1,
        firstWindowId = previousSettings.firstWindowId,
        previousWindowId = previousSettings.windowId,
        windowId = nextWindowId,
    )
    index[pointIndex] = CleanCompactionPoint
    settings[pointIndex] = nextSettings
    tokenCount[pointIndex] = 0L
    this.timestamp[outputIndex] = timestamp
    work[outputIndex] = output
    return outputIndex
}
