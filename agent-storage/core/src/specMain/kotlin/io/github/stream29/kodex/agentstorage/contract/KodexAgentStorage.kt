package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Persisted commit history for one agent thread.
 *
 * Exposes atomic index-level and type-filtered query primitives directly over
 * the unified commit history. Higher-level domain projections live in
 * `io.github.stream29.kodex.agentstorage.contract.ext`.
 */
public interface KodexAgentStorage {
    /** Stable identity locator for this storage backend. */
    public val uri: String

    /** Returns the greatest state index stored in this storage, or `-1` when empty. */
    public suspend fun latestIndex(): Int

    /**
     * Returns the number of commits stored in [range] whose type is in [types],
     * without decoding commit payloads.
     */
    public suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): Int

    /**
     * Returns the earliest [limit] commits in [range] whose type is in [types],
     * ordered ascending by index and [AgentCommitType] ordinal.
     */
    public suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int = Int.MAX_VALUE,
    ): List<IndexedAgentCommit>

    /**
     * Returns the latest [limit] commits in [range] whose type is in [types],
     * ordered ascending by index and [AgentCommitType] ordinal.
     */
    public suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int = Int.MAX_VALUE,
    ): List<IndexedAgentCommit>
}

/** Mutable form of [KodexAgentStorage]. */
public interface MutableKodexAgentStorage : KodexAgentStorage {
    /** Allocates the next state index (`latestIndex() + 1`) and atomically persists [commit]. */
    public suspend fun commit(commit: AgentCommit): Int =
        commit(listOf(commit), Clock.System.now())

    /** Allocates the next state index (`latestIndex() + 1`) and atomically persists [commit] with creation [timestamp]. */
    public suspend fun commit(
        commit: AgentCommit,
        timestamp: Instant,
    ): Int = commit(listOf(commit), timestamp)

    /**
     * Allocates the next state index (`latestIndex() + 1`) and atomically persists
     * all [commits] at that shared index with the current clock timestamp.
     */
    public suspend fun commit(commits: List<AgentCommit>): Int =
        commit(commits, Clock.System.now())

    /**
     * Allocates the next state index (`latestIndex() + 1`) and atomically persists
     * all [commits] at that shared index with creation [timestamp].
     */
    public suspend fun commit(
        commits: List<AgentCommit>,
        timestamp: Instant,
    ): Int

    /** Removes every commit across all types at or after [untilExclusive]. */
    public suspend fun revert(untilExclusive: Int)
}

/** Validates that [commits] is non-empty and contains at most one commit per timeline family. */
public fun validateCommitBatch(commits: List<AgentCommit>) {
    require(commits.isNotEmpty()) { "Commit batch must not be empty." }
    val seenFamilies = HashSet<String>(commits.size)
    for (commit in commits) {
        val family = commit.commitType.legacyTimelineName
        require(seenFamilies.add(family)) {
            "Duplicate commit family '$family' in single commit batch: ${commits.map { it.commitType.wireName }}"
        }
    }
}

/**
 * Returns all commits stored in [range] whose type is in [types],
 * ordered ascending by index and [AgentCommitType] ordinal.
 */
public suspend fun KodexAgentStorage.getCommitsIn(
    range: IntRange,
    types: Set<AgentCommitType> = AgentCommitType.All,
): List<IndexedAgentCommit> = getFirstCommitsIn(range, types, limit = Int.MAX_VALUE)

/**
 * Returns the first [IndexedAgentCommit] stored at exact [index] whose type is in [types], or `null`.
 */
public suspend fun KodexAgentStorage.getCommitExact(
    index: Int,
    types: Set<AgentCommitType> = AgentCommitType.All,
): IndexedAgentCommit? {
    require(index >= 0) { "Index $index must be non-negative." }
    if (types.isEmpty()) return null
    return getFirstCommitsIn(index..index, types, limit = 1).firstOrNull()
}

/**
 * Returns the latest [IndexedAgentCommit] with index `<= [index]` matching [types], or `null`.
 */
public suspend fun KodexAgentStorage.getCommitAtOrBefore(
    index: Int,
    types: Set<AgentCommitType> = AgentCommitType.All,
): IndexedAgentCommit? {
    if (index < 0 || types.isEmpty()) return null
    return getLastCommitsIn(0..index, types, limit = 1).lastOrNull()
}

/**
 * Returns the earliest [IndexedAgentCommit] with index `>= [index]` matching [types], or `null`.
 */
public suspend fun KodexAgentStorage.getCommitAtOrAfter(
    index: Int,
    types: Set<AgentCommitType> = AgentCommitType.All,
): IndexedAgentCommit? {
    if (types.isEmpty()) return null
    val start = index.coerceAtLeast(0)
    return getFirstCommitsIn(start..Int.MAX_VALUE, types, limit = 1).firstOrNull()
}

public suspend fun KodexAgentStorage.getSettingsAtOrNull(index: Int): KodexAgentSettings? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitAtOrBefore(index, AgentCommitType.SettingsTypes)?.commit as? KodexAgentSettings
}

public suspend fun KodexAgentStorage.getSettingsAt(index: Int): KodexAgentSettings =
    requireNotNull(getSettingsAtOrNull(index)) { "No settings are visible at index $index." }

public suspend fun KodexAgentStorage.getLlmRequestMetaAtOrNull(index: Int): LlmRequestMeta? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitAtOrBefore(index, AgentCommitType.LlmRequestMetaTypes)?.commit as? LlmRequestMeta
}

public suspend fun KodexAgentStorage.getUnstableAtOrNull(index: Int): List<UnstableCleanEvent>? {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getCommitAtOrBefore(index, AgentCommitType.UnstableTypes)?.commit as? UnstableSnapshot)?.events
}
