package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn

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
    return getLastCommitsIn(0..at, setOf(AgentCommitType.ContextCompaction), limit = 1)
        .lastOrNull()
        ?.index
}

/**
 * Reconstructs the model-visible message window at [index].
 *
 * When a compaction checkpoint exists at or before [index], projection starts directly
 * at that self-contained `StableContextCompaction` commit (which already carries its
 * bounded `retainedItems` and encrypted compaction summary) and appends commits after
 * the checkpoint through [index], without scanning commits before the checkpoint.
 */
public actual suspend fun KodexAgentStorage.activeMessageWindowAt(
    index: Int,
): List<StableCleanEvent> {
    require(index >= 0) { "Index $index must be non-negative." }
    val contextStart = latestCompactionPointOrNull(index) ?: 0
    return getCommitsIn(contextStart..index, AgentCommitType.CleanEventTypes)
        .map { it.commit as StableCleanEvent }
}
