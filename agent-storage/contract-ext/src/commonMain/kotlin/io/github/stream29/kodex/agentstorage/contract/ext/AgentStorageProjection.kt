package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.valuesDescending
import kotlinx.coroutines.flow.firstOrNull

/**
 * Finds the newest compaction marker in the current storage.
 *
 * Only index-timeline entries are inspected. The flow stops as soon as the
 * first marker is found, so callers do not decode the complete index timeline.
 */
public suspend fun KodexAgentStorage.latestCompactionPointOrNull(): Int? =
    latestCompactionPointOrNull(latestIndex())

/** Finds the newest compaction marker at or before [at]. */
private suspend fun KodexAgentStorage.latestCompactionPointOrNull(at: Int): Int? {
    require(at >= 0) { "Index $at must be non-negative." }
    return index.valuesDescending(at)
        .firstOrNull { (_, entry) -> entry is CleanCompactionPoint }
        ?.first
}

/**
 * Reconstructs the model-visible message window at [index].
 *
 * The part before the latest compaction marker is reduced to the bounded
 * retained prefix. The part from the marker through [index] is preserved
 * exactly, including the context-compaction output.
 */
public suspend fun KodexAgentStorage.activeMessageWindowAt(
    index: Int,
): List<StableCleanEvent> {
    require(index >= 0) { "Index $index must be non-negative." }

    val pointIndex = latestCompactionPointOrNull(index)
    val prefix = if (pointIndex == null) {
        emptyList()
    } else {
        buildCompactionPrefix(pointIndex)
    }
    val contextStart = pointIndex ?: 0
    val indexEvents = this.index.valuesIn(contextStart..index)
    val workEvents = work.valuesIn(contextStart..index)

    val contextWindow = buildList {
        var indexPosition = 0
        var workPosition = 0
        while (indexPosition < indexEvents.size || workPosition < workEvents.size) {
            val indexEntry = indexEvents.getOrNull(indexPosition)
            val workEntry = workEvents.getOrNull(workPosition)
            if (indexEntry != null && (workEntry == null || indexEntry.first <= workEntry.first)) {
                indexPosition += 1
                when (val entry = indexEntry.second) {
                    is StableIndexEvent -> add(entry)
                    is CleanCompactionPoint -> Unit
                }
            } else {
                workPosition += 1
                add(requireNotNull(workEntry).second)
            }
        }
    }
    return prefix + contextWindow
}
