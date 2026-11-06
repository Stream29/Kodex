package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlin.time.Instant

/** Publishes the required snapshot-zero state into an empty AgentStorage. */
public expect suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings)

/**
 * Finds the newest compaction marker in the current storage.
 */
public expect suspend fun KodexAgentStorage.latestCompactionPointOrNull(): Int?

/**
 * Finds the newest compaction marker at or before [at].
 */
public expect suspend fun KodexAgentStorage.latestCompactionPointOrNull(at: Int): Int?

/**
 * Reconstructs the model-visible message window at [index].
 *
 * The part before the latest compaction marker is reduced to the bounded
 * retained prefix. The part from the marker through [index] is preserved
 * exactly, including the context-compaction output.
 */
public expect suspend fun KodexAgentStorage.activeMessageWindowAt(index: Int): List<StableCleanEvent>

/** Appends one compaction point and its provider output at consecutive indexes. */
public expect suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    nextWindowId: String,
    previousSettings: KodexAgentSettings,
): Int

/**
 * Reads the bounded retained prefix before [beforeIndex].
 */
public expect suspend fun KodexAgentStorage.buildCompactionPrefix(beforeIndex: Int): List<CompactionRetainedItem>
