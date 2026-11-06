package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlin.time.Instant

/** Appends one compaction point and its provider output at consecutive indexes. */
public actual suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    nextWindowId: String,
    previousSettings: KodexAgentSettings,
): Int {
    val nextSettings = previousSettings.copy(
        windowNumber = previousSettings.windowNumber + 1,
        firstWindowId = previousSettings.firstWindowId,
        previousWindowId = previousSettings.windowId,
        windowId = nextWindowId,
    )
    commit(
        listOf(
            CleanCompactionPoint,
            nextSettings,
            TokenCountSnapshot.Compaction(0L),
        ),
    )
    return commit(
        listOf(
            CommitTimestamp(timestamp),
            output,
        ),
    )
}
