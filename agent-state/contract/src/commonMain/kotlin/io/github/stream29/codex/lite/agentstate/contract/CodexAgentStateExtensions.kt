package io.github.stream29.codex.lite.agentstate.contract

import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger

/**
 * Requests an explicit user-initiated server-side context compaction.
 */
public suspend fun CodexAgentState.forcedCompact(): Int =
    compact(
        trigger = RemoteCompactionV2Trigger.Manual,
        reason = RemoteCompactionV2Reason.UserRequested,
        phase = RemoteCompactionV2Phase.StandaloneTurn,
    )
