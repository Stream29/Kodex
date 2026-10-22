package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/** Publishes the required snapshot-zero state into an empty AgentStorage. */
public expect suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings)

/**
 * Finds the newest compaction marker in the current storage.
 *
 * Only index-timeline entries are inspected. The flow stops as soon as the
 * first marker is found, so callers do not decode the complete index timeline.
 */
public expect suspend fun KodexAgentStorage.latestCompactionPointOrNull(): Int?

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
 *
 * Indexes are fetched through the timeline flow in exponentially growing
 * ranges. Once the token budget is exhausted, collection is cancelled and no
 * older payloads are decoded.
 */
public expect suspend fun KodexAgentStorage.buildCompactionPrefix(beforeIndex: Int): List<CompactionRetainedItem>
