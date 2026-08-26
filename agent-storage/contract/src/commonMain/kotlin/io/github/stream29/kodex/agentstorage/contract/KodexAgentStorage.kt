package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Persisted state for one agent thread.
 *
 * This contract deliberately contains no parent, child, role, or runtime
 * lifecycle. Recursive topology belongs to AgentSession, while AgentState
 * interprets exactly one initialized storage.
 *
 * A storage accepted by `KodexAgentState` must publish its initial snapshot at
 * index `0`: [settings] and [compaction] must each have a visible value there.
 * An empty storage may exist briefly as a newly spawned AgentSession node, but
 * it cannot represent a legal AgentState.
 *
 * All timelines share one sparse state index space. A state index may contain
 * entries in one timeline or several timelines; use [nextIndex] to enumerate
 * indexes that exist in at least one timeline.
 *
 * Readers should capture [latestIndex] once when they need a stable upper
 * bound for a read. Reading each timeline's own latest index independently may
 * observe different upper bounds.
 *
 * The active model-visible history at a snapshot index is the clean
 * checkpoint projection followed by stable clean events in
 * `[checkpoint.historyBaseIndex, index]` and the current unstable tail.
 *
 * Compose [setWithTransaction] and [revertWithTransaction] when one logical
 * state transition updates multiple timelines. Callers must serialize writers.
 *
 * @property id Backend-derived identity of this storage. Filesystem backends
 * keep it stable across reopen, while transient backends keep it stable for
 * the lifetime of the storage object. It is not an additional persisted
 * timeline or manifest field.
 * @property compaction Sparse checkpoint timeline. `compaction[index]` returns
 * the checkpoint active for the snapshot at `index`.
 * @property settings Sparse agent-thread settings timeline. `settings[index]`
 * returns the model request configuration, agent mode, plan, and goal
 * active for the snapshot at `index`.
 * @property timestamp Sparse timestamp timeline. Entries record the time
 * associated with the state index where they are stored.
 * @property tokenCount Sparse context token-count timeline used for compaction
 * scheduling, not cumulative usage or billing data. Ordinary response entries
 * are OpenAI-reported counts. Every compaction checkpoint writes a synthetic
 * `0` to replace the previous context window's count until the next ordinary
 * response reports a new value.
 * @property stable Sparse completed clean-event timeline. Each stored index
 * contains the event completed by that state transition. Enumerate its stored
 * indexes instead of treating [IndexVersioned.get] as a cumulative history
 * snapshot.
 * @property unstable Sparse unfinished-tool snapshot timeline. Each stored
 * value is the complete ordered set of unfinished clean tool events after that
 * state transition. No visible value means the pending set is empty.
 */
public interface KodexAgentStorage {
    public val id: String
    public val compaction: IndexVersioned<CleanCompactionCheckpoint>
    public val settings: IndexVersioned<KodexAgentSettings>
    public val timestamp: IndexVersioned<Instant>
    public val tokenCount: IndexVersioned<Long>
    public val stable: IndexVersioned<StableCleanEvent>
    public val unstable: IndexVersioned<List<UnstableCleanEvent>>
}

/**
 * Returns the global snapshot boundary.
 *
 * This returns the greatest index stored in any timeline.
 */
public suspend fun KodexAgentStorage.latestIndex(): Int =
    maxOf(
        compaction.latestIndex(),
        settings.latestIndex(),
        timestamp.latestIndex(),
        tokenCount.latestIndex(),
        stable.latestIndex(),
        unstable.latestIndex(),
    )

/**
 * Returns the greatest state index less than or equal to [index] that is
 * stored in any timeline.
 *
 * @param index Inclusive upper bound.
 */
public suspend fun KodexAgentStorage.floorToIndex(index: Int): Int? =
    listOfNotNull(
        compaction.floorToIndex(index),
        settings.floorToIndex(index),
        timestamp.floorToIndex(index),
        tokenCount.floorToIndex(index),
        stable.floorToIndex(index),
        unstable.floorToIndex(index),
    ).maxOrNull()

/**
 * Returns the smallest state index greater than or equal to [index] that is
 * stored in any timeline.
 *
 * @param index Inclusive lower bound.
 */
public suspend fun KodexAgentStorage.ceilToIndex(index: Int): Int? =
    listOfNotNull(
        compaction.ceilToIndex(index),
        settings.ceilToIndex(index),
        timestamp.ceilToIndex(index),
        tokenCount.ceilToIndex(index),
        stable.ceilToIndex(index),
        unstable.ceilToIndex(index),
    ).minOrNull()

/**
 * Returns the first global state index strictly after [index].
 */
public suspend fun KodexAgentStorage.nextIndex(index: Int): Int? {
    return if (index == Int.MAX_VALUE) null else ceilToIndex(index + 1)
}

/**
 * Returns the first global state index strictly before [index].
 */
public suspend fun KodexAgentStorage.prevIndex(index: Int): Int? {
    return if (index == Int.MIN_VALUE) null else floorToIndex(index - 1)
}

/**
 * Mutable form of [KodexAgentStorage].
 *
 * Callers must publish related timeline updates at the same state index and
 * compose operation-level compensation when those updates form one transition.
 */
public interface MutableKodexAgentStorage : KodexAgentStorage {
    public override val compaction: MutableIndexVersioned<CleanCompactionCheckpoint>
    public override val settings: MutableIndexVersioned<KodexAgentSettings>
    public override val timestamp: MutableIndexVersioned<Instant>
    public override val tokenCount: MutableIndexVersioned<Long>
    public override val stable: MutableIndexVersioned<StableCleanEvent>
    public override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>>
}

/**
 * Removes every timeline entry at or after [untilExclusive].
 *
 * This composes the suffix-removal primitive already provided by each
 * [MutableIndexVersioned]. Callers must serialize writers. A boundary of `0`
 * empties the storage for [forkTo]; a live agent state must retain index `0`.
 */
public suspend fun MutableKodexAgentStorage.revert(untilExclusive: Int) {
    compaction.revertWithTransaction(untilExclusive) {
        settings.revertWithTransaction(untilExclusive) {
            timestamp.revertWithTransaction(untilExclusive) {
                tokenCount.revertWithTransaction(untilExclusive) {
                    stable.revertWithTransaction(untilExclusive) {
                        unstable.revert(untilExclusive)
                    }
                }
            }
        }
    }
}

/**
 * Resets [target] and copies this storage into it.
 *
 * [until] is the exclusive state boundary. It may immediately follow any
 * committed stable event; callers own the product semantics of retaining a
 * prefix that ends inside a model/tool exchange.
 *
 * @param until Exclusive state upper bound. It must be greater than zero so
 * the target retains its required initialized snapshot.
 */
public suspend fun KodexAgentStorage.forkTo(
    until: Int,
    target: MutableKodexAgentStorage,
) {
    forkRangeTo(from = 0, until = until, target = target)
}

/**
 * Resets [target] and copies the source state range `[from, until)` into it.
 *
 * Stored timeline indexes are rebased by subtracting [from]. Compaction
 * checkpoints are copied with their history base rebased by the same amount.
 * Callers must choose a range beginning at a valid initialized snapshot.
 */
public suspend fun KodexAgentStorage.forkRangeTo(
    from: Int,
    until: Int,
    target: MutableKodexAgentStorage,
) {
    require(this !== target) { "Cannot fork a storage into itself." }
    require(from >= 0) { "Fork start index must be non-negative." }
    require(until > from) { "Fork range must not be empty." }
    require(until <= latestIndex() + 1) { "Fork range exceeds source storage." }
    target.compaction.revertWithTransaction(0) {
        target.settings.revertWithTransaction(0) {
            target.timestamp.revertWithTransaction(0) {
                target.tokenCount.revertWithTransaction(0) {
                    target.stable.revertWithTransaction(0) {
                        target.unstable.revertWithTransaction(0) {
                            this.compaction.forkStateRangeTo(from, until, target.compaction) { checkpoint ->
                                checkpoint.copy(historyBaseIndex = checkpoint.historyBaseIndex - from)
                            }
                            this.settings.forkStateRangeTo(from, until, target.settings)
                            this.timestamp.forkStateRangeTo(from, until, target.timestamp)
                            this.tokenCount.forkStateRangeTo(from, until, target.tokenCount)
                            this.stable.forkRangeTo(from, until, target.stable)
                            this.unstable.forkStateRangeTo(from, until, target.unstable)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun <T> IndexVersioned<T>.forkStateRangeTo(
    from: Int,
    until: Int,
    target: MutableIndexVersioned<T>,
) = forkStateRangeTo(from, until, target) { value -> value }

private suspend fun <T, R> IndexVersioned<T>.forkStateRangeTo(
    from: Int,
    until: Int,
    target: MutableIndexVersioned<R>,
    transform: (T) -> R,
) {
    require(target.latestIndex() == -1) { "Only an empty target can be forked to." }
    val visibleIndex = floorToIndex(from)
    if (visibleIndex != null) target[0] = transform(this[visibleIndex])
    indexes(from + 1).collect { index ->
        if (index < until) target[index - from] = transform(this[index])
    }
}
