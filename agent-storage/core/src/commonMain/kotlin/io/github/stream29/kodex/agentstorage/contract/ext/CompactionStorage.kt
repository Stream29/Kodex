package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserAnswer
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
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
        val isUserTurnInput = event.isUserTurnInputEvent()
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

public actual suspend fun MutableKodexAgentStorage.createAskUser(
    questions: RequestUserInputArgs,
    timestamp: Instant,
): AskUserEntry {
    val currentLatest = latestIndex()
    require(currentLatest >= 0) { "AskUser updates require an existing state index." }
    val existing = getAskUsersAt(currentLatest)
    val nextAskId = (existing.maxOfOrNull { it.askId } ?: 0) + 1
    val created = AskUserEntry(
        askId = nextAskId,
        questions = questions,
        answer = null,
        createdAt = timestamp,
    )
    commit(listOf(AskUserSnapshot(existing + created)), timestamp)
    return created
}

public actual suspend fun MutableKodexAgentStorage.answerAskUser(
    askId: Int,
    response: RequestUserInputResponse,
    timestamp: Instant,
): AskUserEntry {
    val currentLatest = latestIndex()
    require(currentLatest >= 0) { "AskUser updates require an existing state index." }
    val existing = getAskUsersAt(currentLatest)
    val entryIndex = existing.indexOfFirst { it.askId == askId }
    require(entryIndex >= 0) { "Unknown ask_user askId: $askId" }
    val target = existing[entryIndex]
    check(target.answer == null) { "ask_user #$askId has already been answered." }

    val answeredEntry = target.copy(
        answer = AskUserAnswer(
            response = response,
            answeredAt = timestamp,
        ),
    )
    val updatedEntries = existing.toMutableList().apply {
        set(entryIndex, answeredEntry)
    }
    val existingSteer = getSteerAt(currentLatest)
    val askUserEvent = StableAskUserEvent(
        askId = askId,
        arguments = target.questions,
        response = response,
    )
    commit(
        listOf(
            AskUserSnapshot(updatedEntries),
            SteerSnapshot(existingSteer + askUserEvent),
        ),
        timestamp,
    )
    return answeredEntry
}

private fun StableIndexEvent.isUserTurnInputEvent(): Boolean =
    this is StableUserMessage ||
        this is StableAskUserEvent ||
        this is StableScheduleEvent ||
        this is StableTerminalExitedEvent

private suspend fun KodexAgentStorage.startsNewTurnBefore(
    index: Int,
): Boolean {
    if (index <= 0) return false
    val last = getLastCommitsIn(0 until index, AgentCommitType.TurnStartBoundaryTypes, limit = 1)
        .lastOrNull()
        ?.commit
    return (last as? StableAssistantMessage)?.let { it.phase != MessagePhase.Commentary } ?: false
}
