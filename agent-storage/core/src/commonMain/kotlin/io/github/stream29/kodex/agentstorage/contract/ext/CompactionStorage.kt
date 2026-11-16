package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserAnswer
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlin.time.Instant

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
