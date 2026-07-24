package io.github.stream29.codex.lite.agentstorage.contract

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Publishes the required snapshot-zero state into an empty AgentStorage.
 *
 * This is the canonical initialization path for a raw child returned by
 * `CodexAgentSession.subagents.create()`. The storage must have no entry in
 * any timeline.
 */
@OptIn(ExperimentalUuidApi::class)
public suspend fun MutableCodexAgentStorage.initialize(initialSettings: CodexAgentSettings) {
    require(latestIndex() < 0) { "AgentStorage is already initialized." }
    val windowId = Uuid.generateV7().toString()
    compaction.setWithTransaction(
        index = 0,
        value = CompactionCheckpoint(
            prefix = emptyList(),
            historyBaseIndex = 0,
            windowNumber = 0,
            firstWindowId = windowId,
            windowId = windowId,
        ),
    ) {
        settings[0] = initialSettings
    }
}
