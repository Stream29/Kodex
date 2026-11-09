package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlin.time.Instant

/** Appends one compaction output and its updated settings at a single index. */
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
    return commit(
        listOf(
            output,
            nextSettings,
            LlmRequestMeta.Compaction(0L),
        ),
        timestamp,
    )
}
