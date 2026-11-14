package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlin.time.Clock
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
 */
public expect suspend fun KodexAgentStorage.buildCompactionRetainedItems(
    upToIndex: Int,
): List<CompactionRetainedItem>

/** Appends [events] to the persisted `SteerSnapshot` queue in this storage. */
public expect suspend fun MutableKodexAgentStorage.appendSteer(
    events: List<StableIndexEvent.Steerable>,
): Int

/** Atomically consumes all currently queued `SteerSnapshot` events into the stable timeline. */
public expect suspend fun MutableKodexAgentStorage.consumeSteerQueue(): List<StableIndexEvent.Steerable>

/**
 * Allocates the next agent-local `askId` (`1`-based) and appends an unanswered [AskUserEntry]
 * to `AskUserSnapshot` in this storage.
 */
public expect suspend fun MutableKodexAgentStorage.createAskUser(
    questions: RequestUserInputArgs,
    timestamp: Instant = Clock.System.now(),
): AskUserEntry

/**
 * Records first-time [response] for [askId] in `AskUserSnapshot` and atomically appends the
 * corresponding `StableAskUserEvent` to `SteerSnapshot` at the same storage index.
 *
 * @throws IllegalArgumentException if [askId] does not exist in the current `AskUserSnapshot`.
 * @throws IllegalStateException if [askId] has already been answered.
 */
public expect suspend fun MutableKodexAgentStorage.answerAskUser(
    askId: Int,
    response: RequestUserInputResponse,
    timestamp: Instant = Clock.System.now(),
): AskUserEntry
