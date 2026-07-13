package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import kotlin.time.Instant

/**
 * Persisted state for one agent thread.
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
 * @property history Sparse response history log. Only real
 * [ResponseItem.HistoryItem] entries are stored. Use [IndexVersioned.nextIndex]
 * or [indexes] to enumerate stored history indexes; `history[index]` returns
 * the latest history item visible at a snapshot index and does not imply that
 * [index] itself stores a history item.
 * @property compaction Sparse checkpoint timeline. `compaction[index]` returns
 * the checkpoint active for the snapshot at [index].
 * @property settings Sparse settings timeline. `settings[index]` returns the
 * settings active for the snapshot at [index].
 * @property plan Sparse task-plan timeline. `plan[index]` returns the latest
 * full `update_plan` snapshot active for the snapshot at [index].
 * @property timestamp Sparse timestamp timeline. Entries record the time
 * associated with the state index where they are stored.
 * @property tokenCount Sparse `token_count` timeline. The value is the latest
 * OpenAI-reported context token count used for compaction scheduling, not
 * cumulative usage or billing data. Absence of a new entry at a state index
 * means OpenAI did not report a new count for that transition.
 */
public interface CodexAgentStorage {
    public val history: IndexVersioned<ResponseItem.HistoryItem>
    public val compaction: IndexVersioned<CompactionCheckpoint>
    public val settings: IndexVersioned<CodexAgentSettings>
    public val plan: IndexVersioned<UpdatePlanArgs>
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
        plan.latestIndex(),
        timestamp.latestIndex(),
        tokenCount.latestIndex(),
    )

/**
 * Returns the first state index greater than or equal to [from] that is stored
 * in any timeline.
 *
 * @param from Inclusive lower bound.
 */
public suspend fun CodexAgentStorage.nextIndex(from: Int): Int? =
    listOfNotNull(
        history.nextIndex(from),
        compaction.nextIndex(from),
        settings.nextIndex(from),
        plan.nextIndex(from),
        timestamp.nextIndex(from),
        tokenCount.nextIndex(from),
    ).minOrNull()

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
    public override val plan: MutableIndexVersioned<UpdatePlanArgs>
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
                plan.transaction {
                    timestamp.transaction {
                        tokenCount.transaction {
                            block()
                        }
                    }
                }
            }
        }
    }

/**
 * Forks this storage into an empty [target].
 *
 * [until] is the exclusive state boundary. Callers should pass a
 * stable turn boundary, not an arbitrary index inside an unfinished model/tool
 * exchange.
 *
 * @param until Exclusive state upper bound.
 */
public suspend fun MutableCodexAgentStorage.forkTo(
    until: Int,
    target: MutableCodexAgentStorage,
) {
    target.transaction {
        this.history.forkTo(until, target.history)
        this.compaction.forkTo(until, target.compaction)
        this.settings.forkTo(until, target.settings)
        this.plan.forkTo(until, target.plan)
        this.timestamp.forkTo(until, target.timestamp)
        this.tokenCount.forkTo(until, target.tokenCount)
    }
}
