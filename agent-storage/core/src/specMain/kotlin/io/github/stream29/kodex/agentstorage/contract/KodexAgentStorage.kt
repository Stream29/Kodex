package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
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
     * Returns all distinct commit indexes stored in [range] that contain at least one commit in [types],
     * ordered ascending by index without decoding commit payloads.
     */
    public suspend fun indexesIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<Int>

    /**
     * Returns all commits stored at exact [index] whose type is in [types],
     * ordered by [AgentCommitType] ordinal.
     */
    public suspend fun getCommitsExact(
        index: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<AgentCommit>

    /**
     * Returns all commits stored in [range] whose type is in [types],
     * ordered ascending by index and [AgentCommitType] ordinal.
     */
    public suspend fun getCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<IndexedAgentCommit>

    /**
     * Returns the earliest [limit] commits in [range] whose type is in [types],
     * ordered ascending by index and [AgentCommitType] ordinal.
     */
    public suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int,
    ): List<IndexedAgentCommit>

    /**
     * Returns the latest [limit] commits in [range] whose type is in [types],
     * ordered ascending by index and [AgentCommitType] ordinal.
     */
    public suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int,
    ): List<IndexedAgentCommit>
}

/** Mutable form of [KodexAgentStorage]. */
public interface MutableKodexAgentStorage : KodexAgentStorage {
    /** Allocates the next state index (`latestIndex() + 1`) and atomically persists [commit]. */
    public suspend fun commit(commit: AgentCommit): Int = commit(listOf(commit))

    /**
     * Allocates the next state index (`latestIndex() + 1`) and atomically persists
     * all [commits] at that shared index.
     */
    public suspend fun commit(commits: List<AgentCommit>): Int

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

/** Returns the greatest index `<= [index]` containing at least one commit in [types], or `null`. */
public suspend fun KodexAgentStorage.floorToIndex(
    index: Int,
    types: Set<AgentCommitType> = AgentCommitType.All,
): Int? {
    if (index < 0 || types.isEmpty()) return null
    return getLastCommitsIn(0..index, types, limit = 1).lastOrNull()?.index
}

/** Returns the smallest index `>= [index]` containing at least one commit in [types], or `null`. */
public suspend fun KodexAgentStorage.ceilToIndex(
    index: Int,
    types: Set<AgentCommitType> = AgentCommitType.All,
): Int? {
    if (types.isEmpty()) return null
    val latest = latestIndex()
    val start = index.coerceAtLeast(0)
    if (latest < start) return null
    return getFirstCommitsIn(start..latest, types, limit = 1).firstOrNull()?.index
}

public suspend fun KodexAgentStorage.getIndexEntryExact(index: Int): CleanIndexEntry? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitsExact(index, AgentCommitType.IndexTypes).singleOrNull() as? CleanIndexEntry
}

public suspend fun KodexAgentStorage.getIndexEntryAtOrNull(index: Int): CleanIndexEntry? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getLastCommitsIn(0..index, AgentCommitType.IndexTypes, limit = 1)
        .lastOrNull()
        ?.commit as? CleanIndexEntry
}

public suspend fun KodexAgentStorage.getIndexEntryAt(index: Int): CleanIndexEntry =
    requireNotNull(getIndexEntryAtOrNull(index)) { "No index entry is visible at index $index." }

public suspend fun KodexAgentStorage.getWorkEventExact(index: Int): StableWorkEvent? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitsExact(index, AgentCommitType.WorkTypes).singleOrNull() as? StableWorkEvent
}

public suspend fun KodexAgentStorage.getWorkEventAtOrNull(index: Int): StableWorkEvent? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getLastCommitsIn(0..index, AgentCommitType.WorkTypes, limit = 1)
        .lastOrNull()
        ?.commit as? StableWorkEvent
}

public suspend fun KodexAgentStorage.getWorkEventAt(index: Int): StableWorkEvent =
    requireNotNull(getWorkEventAtOrNull(index)) { "No work event is visible at index $index." }

public suspend fun KodexAgentStorage.getSettingsExact(index: Int): KodexAgentSettings? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitsExact(index, AgentCommitType.SettingsTypes).singleOrNull() as? KodexAgentSettings
}

public suspend fun KodexAgentStorage.getSettingsAtOrNull(index: Int): KodexAgentSettings? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getLastCommitsIn(0..index, AgentCommitType.SettingsTypes, limit = 1)
        .lastOrNull()
        ?.commit as? KodexAgentSettings
}

public suspend fun KodexAgentStorage.getSettingsAt(index: Int): KodexAgentSettings =
    requireNotNull(getSettingsAtOrNull(index)) { "No settings are visible at index $index." }

public suspend fun KodexAgentStorage.getTimestampExact(index: Int): Instant? {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getCommitsExact(index, AgentCommitType.TimestampTypes).singleOrNull() as? CommitTimestamp)?.instant
}

public suspend fun KodexAgentStorage.getTimestampAtOrNull(index: Int): Instant? {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getLastCommitsIn(0..index, AgentCommitType.TimestampTypes, limit = 1)
        .lastOrNull()
        ?.commit as? CommitTimestamp)?.instant
}

public suspend fun KodexAgentStorage.getTimestampAt(index: Int): Instant =
    requireNotNull(getTimestampAtOrNull(index)) { "No timestamp is visible at index $index." }

public suspend fun KodexAgentStorage.getTokenCountExact(index: Int): TokenCountSnapshot? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getCommitsExact(index, AgentCommitType.TokenCountTypes).singleOrNull() as? TokenCountSnapshot
}

public suspend fun KodexAgentStorage.getTokenCountAtOrNull(index: Int): TokenCountSnapshot? {
    require(index >= 0) { "Index $index must be non-negative." }
    return getLastCommitsIn(0..index, AgentCommitType.TokenCountTypes, limit = 1)
        .lastOrNull()
        ?.commit as? TokenCountSnapshot
}

public suspend fun KodexAgentStorage.getTokenCountAt(index: Int): TokenCountSnapshot =
    requireNotNull(getTokenCountAtOrNull(index)) { "No token count snapshot is visible at index $index." }

public suspend fun KodexAgentStorage.getUnstableExact(index: Int): List<UnstableCleanEvent>? {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getCommitsExact(index, AgentCommitType.UnstableTypes).singleOrNull() as? UnstableSnapshot)?.events
}

public suspend fun KodexAgentStorage.getUnstableAtOrNull(index: Int): List<UnstableCleanEvent>? {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getLastCommitsIn(0..index, AgentCommitType.UnstableTypes, limit = 1)
        .lastOrNull()
        ?.commit as? UnstableSnapshot)?.events
}

public suspend fun KodexAgentStorage.getUnstableAt(index: Int): List<UnstableCleanEvent> =
    requireNotNull(getUnstableAtOrNull(index)) { "No unstable snapshot is visible at index $index." }

public suspend fun KodexAgentStorage.latestIndexOf(
    types: Set<AgentCommitType> = AgentCommitType.All,
): Int = floorToIndex(latestIndex(), types) ?: -1

public suspend fun KodexAgentStorage.latestUnstableIndex(): Int =
    latestIndexOf(AgentCommitType.UnstableTypes)
