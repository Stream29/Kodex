package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.CompactionTrigger

/**
 * Replaces the thread name in the latest settings snapshot.
 */
public suspend fun CodexAgentState.renameThread(threadName: String): Int {
    val settings = storage.settings[latestIndex.value]
    return updateSettings(settings.copy(threadName = threadName))
}

/**
 * Requests an explicit user-initiated server-side context compaction.
 */
public suspend fun CodexAgentState.forcedCompact(): Int =
    compact(
        trigger = CompactionTrigger.Manual,
        reason = CompactionReason.UserRequested,
        phase = CompactionPhase.StandaloneTurn,
    )

/**
 * Completes all currently pending local tool calls as failed with
 * `user interrupt`.
 *
 * Every individual transition is performed by [CodexAgentState.completeToolCall],
 * preserving its existing validation and atomic stable/unstable history update.
 */
public suspend fun CodexAgentState.clearPending(): Int {
    var index = latestIndex.value
    while (true) {
        val pending = state.value as? CodexAgentStateValue.ToolPending ?: return index
        index = completeToolCall(pending.events.first().toFailedToolEvent(UserInterruptToolMessage))
    }
}

private const val UserInterruptToolMessage: String = "user interrupt"
