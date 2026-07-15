package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.time.Instant

/**
 * Persisted state for one agent thread.
 *
 * A storage accepted by `CodexAgentState` must publish its initial snapshot at
 * index `0`: [settings] and [compaction] must each have a visible value there.
 * An empty storage cannot represent a legal agent state.
 *
 * All timelines share one sparse state index space. A state index may contain
 * entries in one timeline or several timelines; use [nextIndex] to enumerate
 * indexes that exist in at least one timeline.
 *
 * Readers should capture [latestIndex] once when they need a stable upper
 * bound for a read. Reading each timeline's own latest index independently may
 * observe different upper bounds.
 *
 * The active model-visible history at a snapshot index is:
 *
 * ```kotlin
 * val checkpoint = compaction[index]
 * checkpoint.prefix + stored history items in [checkpoint.historyBaseIndex, index]
 * ```
 *
 * Use [transaction] when one logical state transition updates multiple
 * timelines. Transactions provide rollback on failure or cancellation; callers
 * must still serialize concurrent writers.
 *
 * @property id Stable identity of this storage-backed Codex thread. Request
 * projection combines it with a compaction window number for the Codex wire
 * `window_id`.
 * @property history Sparse response history log. Only real
 * [ResponseItem.HistoryItem] entries are stored. Use [IndexVersioned.nextIndex]
 * or [indexes] to enumerate stored history indexes; `history[index]` returns
 * the latest history item visible at a snapshot index and does not imply that
 * [index] itself stores a history item.
 * @property compaction Sparse checkpoint timeline. `compaction[index]` returns
 * the checkpoint active for the snapshot at [index].
 * @property settings Sparse agent-thread settings timeline. `settings[index]`
 * returns the model request configuration, collaboration mode, plan, and goal
 * active for the snapshot at [index].
 * @property timestamp Sparse timestamp timeline. Entries record the time
 * associated with the state index where they are stored.
 * @property tokenCount Sparse `token_count` timeline. The value is the latest
 * OpenAI-reported context token count used for compaction scheduling, not
 * cumulative usage or billing data. Absence of a new entry at a state index
 * means OpenAI did not report a new count for that transition.
 */
public interface CodexAgentStorage {
    public val id: String
    public val history: IndexVersioned<ResponseItem.HistoryItem>
    public val compaction: IndexVersioned<CompactionCheckpoint>
    public val settings: IndexVersioned<CodexAgentSettings>
    public val timestamp: IndexVersioned<Instant>
    public val tokenCount: IndexVersioned<Long>
}

/**
 * Returns the global snapshot boundary.
 *
 * This returns the greatest index stored in any timeline.
 */
public suspend fun CodexAgentStorage.latestIndex(): Int =
    maxOf(
        history.latestIndex(),
        compaction.latestIndex(),
        settings.latestIndex(),
        timestamp.latestIndex(),
        tokenCount.latestIndex(),
    )

/**
 * Returns the greatest state index less than or equal to [index] that is
 * stored in any timeline.
 *
 * @param index Inclusive upper bound.
 */
public suspend fun CodexAgentStorage.floorToIndex(index: Int): Int? =
    listOfNotNull(
        history.floorToIndex(index),
        compaction.floorToIndex(index),
        settings.floorToIndex(index),
        timestamp.floorToIndex(index),
        tokenCount.floorToIndex(index),
    ).maxOrNull()

/**
 * Returns the smallest state index greater than or equal to [index] that is
 * stored in any timeline.
 *
 * @param index Inclusive lower bound.
 */
public suspend fun CodexAgentStorage.ceilToIndex(index: Int): Int? =
    listOfNotNull(
        history.ceilToIndex(index),
        compaction.ceilToIndex(index),
        settings.ceilToIndex(index),
        timestamp.ceilToIndex(index),
        tokenCount.ceilToIndex(index),
    ).minOrNull()

/**
 * Returns the first global state index strictly after [index].
 */
public suspend fun CodexAgentStorage.nextIndex(index: Int): Int? {
    return if (index == Int.MAX_VALUE) null else ceilToIndex(index + 1)
}

/**
 * Returns the first global state index strictly before [index].
 */
public suspend fun CodexAgentStorage.prevIndex(index: Int): Int? {
    return if (index == Int.MIN_VALUE) null else floorToIndex(index - 1)
}

/**
 * Mutable form of [CodexAgentStorage].
 *
 * Callers must publish related timeline updates at the same state index and use
 * [transaction] when those updates belong to one logical transition.
 */
public interface MutableCodexAgentStorage : CodexAgentStorage {
    public override val history: MutableIndexVersioned<ResponseItem.HistoryItem>
    public override val compaction: MutableIndexVersioned<CompactionCheckpoint>
    public override val settings: MutableIndexVersioned<CodexAgentSettings>
    public override val timestamp: MutableIndexVersioned<Instant>
    public override val tokenCount: MutableIndexVersioned<Long>
}

/**
 * Runs one externally serialized append transaction across every storage
 * timeline.
 *
 * If [block] fails or is cancelled, each timeline is reverted to the tail it had
 * when the transaction started. [block] must not revert entries that existed
 * before the transaction.
 */
public suspend inline fun <R> MutableCodexAgentStorage.transaction(block: () -> R): R =
    history.transaction {
        compaction.transaction {
            settings.transaction {
                timestamp.transaction {
                    tokenCount.transaction {
                        block()
                    }
                }
            }
        }
    }

/**
 * Resets [target] and copies this storage into it.
 *
 * [until] is the exclusive state boundary. Callers should pass a
 * stable turn boundary, not an arbitrary index inside an unfinished model/tool
 * exchange.
 *
 * [target] keeps its own [CodexAgentStorage.id], so a fork represents a new
 * Codex thread even when its initial history matches this storage.
 *
 * @param until Exclusive state upper bound. It must be greater than zero so
 * the target retains its required initialized snapshot.
 */
public suspend fun MutableCodexAgentStorage.forkTo(
    until: Int,
    target: MutableCodexAgentStorage,
) {
    require(this !== target) { "Cannot fork a storage into itself." }
    require(until > 0) { "A fork must include the initialized state at index 0." }
    target.revertAll()
    target.transaction {
        this.history.forkTo(until, target.history)
        this.compaction.forkTo(until, target.compaction)
        this.settings.forkTo(until, target.settings)
        this.timestamp.forkTo(until, target.timestamp)
        this.tokenCount.forkTo(until, target.tokenCount)
    }
}

private suspend fun MutableCodexAgentStorage.revertAll() {
    history.revert(0)
    compaction.revert(0)
    settings.revert(0)
    timestamp.revert(0)
    tokenCount.revert(0)
}
