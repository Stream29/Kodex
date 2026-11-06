package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage

/**
 * Finds the newest compaction marker in the current storage.
 */
public actual suspend fun KodexAgentStorage.latestCompactionPointOrNull(): Int? {
    val latest = latestIndex()
    if (latest < 0) return null
    return latestCompactionPointOrNull(latest)
}

/** Finds the newest compaction marker at or before [at]. */
public actual suspend fun KodexAgentStorage.latestCompactionPointOrNull(at: Int): Int? {
    require(at >= 0) { "Index $at must be non-negative." }
    return getLastCommitsIn(0..at, setOf(AgentCommitType.CompactionPoint), limit = 1)
        .lastOrNull()
        ?.index
}

/**
 * Reconstructs the model-visible message window at [index].
 *
 * The part before the latest compaction marker is reduced to the bounded
 * retained prefix. The part from the marker through [index] is preserved
 * exactly, including the context-compaction output.
 */
public actual suspend fun KodexAgentStorage.activeMessageWindowAt(
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
    val contextWindow = getCommitsIn(contextStart..index, AgentCommitType.CleanEventTypes)
        .map { it.commit as StableCleanEvent }
    return prefix + contextWindow
}
