package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.ResponseItem

/**
 * Raw persisted state for one agent thread.
 *
 * [history] is the publication boundary for the whole storage. Writers that
 * update several timelines for the same logical step must write [settings] and
 * [compaction] first, then publish the corresponding [history] index last.
 *
 * Readers obtain a coherent snapshot by calling [latestIndex] exactly once and
 * using that fixed index to read every timeline. Reading each timeline's own
 * latest index independently may observe a mixed state.
 *
 * The active model-visible history at a snapshot index is:
 *
 * ```kotlin
 * val checkpoint = compaction[index]
 * checkpoint.prefix + history items in [checkpoint.historyBaseIndex, index]
 * ```
 *
 * The storage contract only defines visibility ordering. Concrete
 * implementations may still use transactions internally, but callers should not
 * require this interface to expose transaction primitives.
 *
 * @property history Append-only raw response-item log. Every index from `0`
 * through [latestIndex] is readable.
 * @property compaction Sparse checkpoint timeline. `compaction[index]` returns
 * the checkpoint active for the snapshot at [index].
 * @property settings Sparse settings timeline. `settings[index]` returns the
 * settings active for the snapshot at [index].
 */
public interface CodexAgentRawDataStorage {
    public val history: IndexVersioned<ResponseItem>
    public val compaction: IndexVersioned<CompactionCheckpoint>
    public val settings: IndexVersioned<CodexAgentSettings>
}

/**
 * Returns the global snapshot boundary.
 *
 * This intentionally delegates to [CodexAgentRawDataStorage.history] because a
 * history entry is the commit marker for related settings and compaction
 * updates.
 */
public suspend fun CodexAgentRawDataStorage.latestIndex(): Int = history.latestIndex()

/**
 * Mutable form of [CodexAgentRawDataStorage].
 *
 * Callers are responsible for publishing related timeline updates in the order
 * required by [CodexAgentRawDataStorage]: settings and compaction first, history
 * last.
 */
public interface MutableCodexAgentRawDataStorage : CodexAgentRawDataStorage {
    public override val history: MutableIndexVersioned<ResponseItem>
    public override val compaction: MutableIndexVersioned<CompactionCheckpoint>
    public override val settings: MutableIndexVersioned<CodexAgentSettings>
}

/**
 * Forks this storage into an empty [target].
 *
 * [until] is the exclusive raw-history item boundary. Callers should pass a
 * stable turn boundary, not an arbitrary index inside an unfinished model/tool
 * exchange.
 *
 * @param until Exclusive raw-history upper bound.
 */
public suspend fun MutableCodexAgentRawDataStorage.forkTo(
    until: Int,
    target: MutableCodexAgentRawDataStorage
) {
    this.history.forkTo(until, target.history)
    this.compaction.forkTo(until, target.compaction)
    this.settings.forkTo(until, target.settings)
}
