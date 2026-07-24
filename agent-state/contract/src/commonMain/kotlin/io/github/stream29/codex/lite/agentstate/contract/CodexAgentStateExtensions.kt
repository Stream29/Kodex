package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.CompactionTrigger

/**
 * Replaces the thread name in the latest settings snapshot.
 */
public suspend fun CodexAgentState.renameThread(threadName: String): Int =
    updateSettings(
        storage.settings[latestIndex.value].copy(threadName = threadName),
    )

/**
 * Requests an explicit user-initiated server-side context compaction.
 */
public suspend fun CodexAgentState.forcedCompact(): Int =
    compact(
        trigger = CompactionTrigger.Manual,
        reason = CompactionReason.UserRequested,
        phase = CompactionPhase.StandaloneTurn,
    )
