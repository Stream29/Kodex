package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Persisted state for one agent thread.
 *
 * The contract contains the six independent timelines and their generic
 * primitives. Higher-level traversal and domain mutation helpers live in
 * `agent-storage-contract-ext`.
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

/** Mutable form of [KodexAgentStorage]. */
public interface MutableKodexAgentStorage : KodexAgentStorage {
    public override val index: MutableIndexVersioned<CleanIndexEntry>
    public override val work: MutableIndexVersioned<StableWorkEvent>
    public override val settings: MutableIndexVersioned<KodexAgentSettings>
    public override val timestamp: MutableIndexVersioned<Instant>
    public override val tokenCount: MutableIndexVersioned<Long>
    public override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>>
}

/** Returns the greatest state index stored in any timeline. */
public suspend fun KodexAgentStorage.latestIndex(): Int =
    maxOf(
        index.latestIndex(),
        work.latestIndex(),
        settings.latestIndex(),
        timestamp.latestIndex(),
        tokenCount.latestIndex(),
        unstable.latestIndex(),
    )

/** Returns the greatest global index less than or equal to [index]. */
public suspend fun KodexAgentStorage.floorToIndex(index: Int): Int? =
    listOfNotNull(
        this.index.floorToIndex(index),
        work.floorToIndex(index),
        settings.floorToIndex(index),
        timestamp.floorToIndex(index),
        tokenCount.floorToIndex(index),
        unstable.floorToIndex(index),
    ).maxOrNull()

/** Returns the smallest global index greater than or equal to [index]. */
public suspend fun KodexAgentStorage.ceilToIndex(index: Int): Int? =
    listOfNotNull(
        this.index.ceilToIndex(index),
        work.ceilToIndex(index),
        settings.ceilToIndex(index),
        timestamp.ceilToIndex(index),
        tokenCount.ceilToIndex(index),
        unstable.ceilToIndex(index),
    ).minOrNull()

/** Removes every timeline entry at or after [untilExclusive]. */
public suspend fun MutableKodexAgentStorage.revert(untilExclusive: Int) {
    index.revert(untilExclusive)
    work.revert(untilExclusive)
    settings.revert(untilExclusive)
    timestamp.revert(untilExclusive)
    tokenCount.revert(untilExclusive)
    unstable.revert(untilExclusive)
}
