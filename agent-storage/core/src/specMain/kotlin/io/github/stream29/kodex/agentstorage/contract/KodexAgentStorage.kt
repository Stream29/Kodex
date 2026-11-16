package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.MessagePhase
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persisted commit history for one agent thread.
 *
 * Exposes atomic index-level and type-filtered query primitives directly over
 * the unified commit history. Higher-level domain projections live in
 * `io.github.stream29.kodex.agentstorage.contract.ext`.
 */
public interface KodexAgentStorage {
    /**
     * Owning session/agent identifier when backed by a session repository, or `null`
     * for standalone storage instances not registered in a session repository.
     */
    public val agentId: Int?
        get() = null

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

    /**
     * Removes every commit across all types at or after [untilExclusive], and clears any
     * remaining unconsumed [SteerSnapshot] state so historical steer does not reappear.
     */
    public suspend fun revert(untilExclusive: Int)

    /**
     * Appends [events] to the persisted [SteerSnapshot] queue and returns the committed index.
     *
     * An empty [events] list is a no-op and returns [latestIndex].
     */
    public suspend fun steer(events: List<StableIndexEvent.Steerable>): Int {
        val currentLatest = latestIndex()
        require(currentLatest >= 0) { "Steer updates require an existing state index." }
        if (events.isEmpty()) return currentLatest
        val existing = getSteerAt(currentLatest)
        return commit(SteerSnapshot(existing + events))
    }

    /**
     * Atomically consumes all currently queued [SteerSnapshot] events into the stable timeline.
     */
    @OptIn(ExperimentalUuidApi::class)
    public suspend fun consumeSteer(): List<StableIndexEvent.Steerable> {
        val currentLatest = latestIndex()
        if (currentLatest < 0) return emptyList()
        val pendingTools = getUnstableAtOrNull(currentLatest).orEmpty()
            .filterIsInstance<PendingToolEvent>()
        require(pendingTools.isEmpty()) {
            "Cannot consume steer while local tool calls are pending."
        }
        val events = getSteerAt(currentLatest)
        if (events.isEmpty()) return emptyList()
        val timestamp = Clock.System.now()
        var currentSettings = getSettingsAt(currentLatest)
        var previousMessageStartsNewTurn = startsNewTurnBefore(currentLatest + 1)
        for ((i, event) in events.withIndex()) {
            val isUserTurnInput = event is StableUserMessage ||
                event is StableAskUserEvent ||
                event is StableScheduleEvent ||
                event is StableTerminalExitedEvent
            val startsNewTurn = isUserTurnInput && previousMessageStartsNewTurn
            val commits = buildList<AgentCommit> {
                if (startsNewTurn) {
                    currentSettings = currentSettings.copy(
                        turnId = Uuid.generateV7().toString(),
                    )
                    add(currentSettings)
                }
                add(event)
                add(SteerSnapshot(events.drop(i + 1)))
            }
            commit(commits, timestamp)
            previousMessageStartsNewTurn = when {
                event is StableAssistantMessage -> event.phase != MessagePhase.Commentary
                isUserTurnInput -> false
                else -> previousMessageStartsNewTurn
            }
        }
        return events
    }
}

private suspend fun KodexAgentStorage.startsNewTurnBefore(index: Int): Boolean {
    if (index <= 0) return false
    val last = getLastCommitsIn(0 until index, AgentCommitType.TurnStartBoundaryTypes, limit = 1)
        .lastOrNull()
        ?.commit
    return (last as? StableAssistantMessage)?.let { it.phase != MessagePhase.Commentary } ?: false
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

public suspend fun KodexAgentStorage.getSteerAt(index: Int): List<StableIndexEvent.Steerable> {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getCommitAtOrBefore(index, AgentCommitType.SteerTypes)?.commit as? SteerSnapshot)?.events ?: emptyList()
}

public suspend fun KodexAgentStorage.getAskUsersAt(index: Int): List<AskUserEntry> {
    require(index >= 0) { "Index $index must be non-negative." }
    return (getCommitAtOrBefore(index, AgentCommitType.AskUserTypes)?.commit as? AskUserSnapshot)?.entries ?: emptyList()
}
