package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.openai.MessagePhase
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Appends one compaction output and its updated settings at a single index. */
public actual suspend fun MutableKodexAgentStorage.appendCompaction(
    output: StableContextCompaction,
    timestamp: Instant,
    nextWindowId: String,
    previousSettings: KodexAgentSettings,
): Int {
    val nextSettings = previousSettings.copy(
        windowNumber = previousSettings.windowNumber + 1,
        firstWindowId = previousSettings.firstWindowId,
        previousWindowId = previousSettings.windowId,
        windowId = nextWindowId,
    )
    return commit(
        listOf(
            output,
            nextSettings,
            LlmRequestMeta.Compaction(0L),
        ),
        timestamp,
    )
}

/** Appends [events] to the persisted `SteerSnapshot` queue in this storage. */
public actual suspend fun MutableKodexAgentStorage.appendSteer(
    events: List<StableIndexEvent.Steerable>,
): Int {
    val currentLatest = latestIndex()
    require(currentLatest >= 0) { "Steer updates require an existing state index." }
    if (events.isEmpty()) return currentLatest
    val existing = getSteerAt(currentLatest)
    return commit(SteerSnapshot(existing + events))
}

/** Atomically consumes all currently queued `SteerSnapshot` events into the stable timeline. */
@OptIn(ExperimentalUuidApi::class)
public actual suspend fun MutableKodexAgentStorage.consumeSteerQueue(): List<StableIndexEvent.Steerable> {
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
        val startsNewTurn = event is StableUserMessage && previousMessageStartsNewTurn
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
        previousMessageStartsNewTurn = when (event) {
            is StableAssistantMessage -> event.phase != MessagePhase.Commentary
            is StableUserMessage -> false
            else -> previousMessageStartsNewTurn
        }
    }
    return events
}

private suspend fun KodexAgentStorage.startsNewTurnBefore(
    index: Int,
): Boolean {
    if (index <= 0) return false
    val last = getLastCommitsIn(0 until index, AgentCommitType.TurnStartBoundaryTypes, limit = 1)
        .lastOrNull()
        ?.commit
    return (last as? StableAssistantMessage)?.let { it.phase != MessagePhase.Commentary } ?: false
}
