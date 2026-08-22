package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Publishes the required snapshot-zero state into an empty AgentStorage.
 *
 * This is the canonical initialization path for a raw child returned by
 * `KodexAgentSession.subagents.create()`. The storage must have no entry in
 * any timeline.
 */
@OptIn(ExperimentalUuidApi::class)
public suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings) {
    require(latestIndex() < 0) { "AgentStorage is already initialized." }
    val windowId = Uuid.generateV7().toString()
    compaction.setWithTransaction(
        index = 0,
        value = CleanCompactionCheckpoint(
            prefix = emptyList(),
            historyBaseIndex = 0,
            windowNumber = 0,
            firstWindowId = windowId,
            windowId = windowId,
        ),
    ) {
        timestamp[0] = Clock.System.now()
        settings[0] = initialSettings
        tokenCount[0] = 0L
    }
}
