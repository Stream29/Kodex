package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
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
 * When a compaction checkpoint exists at or before [index], projection starts directly
 * at that self-contained [StableContextCompaction] commit (which already carries its
 * bounded `retainedItems` and encrypted compaction summary) and appends commits after
 * the checkpoint through [index], without scanning commits before the checkpoint.
 */
public expect suspend fun KodexAgentStorage.activeMessageWindowAt(index: Int): List<StableCleanEvent>

/** Appends one self-contained compaction output and its updated settings at a single index. */
public expect suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    nextWindowId: String,
    previousSettings: KodexAgentSettings,
): Int

/**
 * Computes the bounded retained prefix (up to the 64,000-token retention budget) for a new
 * compaction checkpoint covering commits through [upToIndex].
 *
 * Reads the previous compaction checkpoint's `retainedItems` (if any) and merges them in
 * index order with retained index commits in `(previousCompactionIndex + 1)..upToIndex` and
 * any caller-supplied [additionalRetainedByIndex] (such as virtual [CompactionRetainedItem]
 * entries synthesized from completed `ask_user` interactions at their completion index).
 */
public expect suspend fun KodexAgentStorage.buildCompactionRetainedItems(
    upToIndex: Int,
    additionalRetainedByIndex: Map<Int, List<CompactionRetainedItem>> = emptyMap(),
): List<CompactionRetainedItem>

/** Appends [events] to the persisted `SteerSnapshot` queue in this storage. */
public expect suspend fun MutableKodexAgentStorage.appendSteer(
    events: List<StableIndexEvent.Steerable>,
): Int

/** Atomically consumes all currently queued `SteerSnapshot` events into the stable timeline. */
public expect suspend fun MutableKodexAgentStorage.consumeSteerQueue(): List<StableIndexEvent.Steerable>
