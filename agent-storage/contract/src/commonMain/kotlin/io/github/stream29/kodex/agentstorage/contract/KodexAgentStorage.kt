package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Persisted state for one agent thread.
 *
 * This contract deliberately contains no parent, child, role, or runtime
 * lifecycle. The session repository owns the root session lifecycle, while
 * AgentState interprets exactly one initialized storage.
 *
 * A storage accepted by `KodexAgentState` must publish its initial snapshot at
 * index `0`: [settings] and [index] must each have a visible value there.
 * An empty storage may exist briefly as a newly created root session, but it
 * cannot represent a legal AgentState.
 *
 * All timelines share one sparse state index space. A state index may contain
 * entries in one timeline or several timelines; use [nextIndex] to enumerate
 * indexes that exist in at least one timeline.
 *
 * Readers should capture [latestIndex] once when they need a stable upper
 * bound for a read. Reading each timeline's own latest index independently may
 * observe different upper bounds.
 *
 * Compose [setWithTransaction] and [revertWithTransaction] when one logical
 * state transition updates multiple timelines. Callers must serialize writers.
 *
 * @property id Backend-derived identity of this storage. Filesystem backends
 * keep it stable across reopen, while transient backends keep it stable for
 * the lifetime of the storage object. It is not an additional persisted
 * timeline or manifest field.
 * @property index Sparse index-entry timeline. It stores complete standalone
 * stable index events and context-window compaction points.
 * @property work Sparse stable work-event timeline. Each stored index contains
 * one completed event. The first work entry after every non-initial compaction
 * point is a context-compaction event; writers enforce that relationship.
 * @property settings Sparse agent-thread settings timeline. `settings[index]`
 * returns the model request configuration, plan, and goal active for the
 * snapshot at `index`.
 * @property timestamp Sparse timestamp timeline. Entries record the time
 * associated with the state index where they are stored.
 * @property tokenCount Sparse context token-count timeline used for compaction
 * scheduling, not cumulative usage or billing data. Ordinary response entries
 * are OpenAI-reported counts. Every compaction point writes a synthetic
 * `0` to replace the previous context window's count until the next ordinary
 * response reports a new value.
 * @property unstable Sparse unfinished-tool snapshot timeline. Each stored
 * value is the complete ordered set of unfinished clean tool events after that
 * state transition. No visible value means the pending set is empty.
 */
public interface KodexAgentStorage {
    public val id: String
    public val index: IndexVersioned<CleanIndexEntry>
    public val work: IndexVersioned<StableWorkEvent>
    public val settings: IndexVersioned<KodexAgentSettings>
    public val timestamp: IndexVersioned<Instant>
    public val tokenCount: IndexVersioned<Long>
    public val unstable: IndexVersioned<List<UnstableCleanEvent>>
}

/**
 * Returns the global snapshot boundary.
 *
 * This returns the greatest index stored in any timeline.
 */
public suspend fun KodexAgentStorage.latestIndex(): Int =
    maxOf(
        index.latestIndex(),
        work.latestIndex(),
        settings.latestIndex(),
        timestamp.latestIndex(),
        tokenCount.latestIndex(),
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
        this.index.floorToIndex(index),
        work.floorToIndex(index),
        settings.floorToIndex(index),
        timestamp.floorToIndex(index),
        tokenCount.floorToIndex(index),
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
        this.index.ceilToIndex(index),
        work.ceilToIndex(index),
        settings.ceilToIndex(index),
        timestamp.ceilToIndex(index),
        tokenCount.ceilToIndex(index),
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
    public override val index: MutableIndexVersioned<CleanIndexEntry>
    public override val work: MutableIndexVersioned<StableWorkEvent>
    public override val settings: MutableIndexVersioned<KodexAgentSettings>
    public override val timestamp: MutableIndexVersioned<Instant>
    public override val tokenCount: MutableIndexVersioned<Long>
    public override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>>
}

/**
 * Removes every timeline entry at or after [untilExclusive].
 *
 * This composes the suffix-removal primitive already provided by each
 * [MutableIndexVersioned]. Callers must serialize writers. A boundary of `0`
 * empties the storage; a live agent state must retain index `0`.
 */
public suspend fun MutableKodexAgentStorage.revert(untilExclusive: Int) {
    index.revertWithTransaction(untilExclusive) {
        work.revertWithTransaction(untilExclusive) {
            settings.revertWithTransaction(untilExclusive) {
                timestamp.revertWithTransaction(untilExclusive) {
                    tokenCount.revertWithTransaction(untilExclusive) {
                        unstable.revert(untilExclusive)
                    }
                }
            }
        }
    }
}
