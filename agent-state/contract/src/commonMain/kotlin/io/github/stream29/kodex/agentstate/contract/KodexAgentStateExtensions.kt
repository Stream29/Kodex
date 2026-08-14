package io.github.stream29.kodex.agentstate.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger

/**
 * Replaces only the thread name in the latest settings snapshot.
 */
public suspend fun KodexAgentState.updateThreadName(threadName: String): Int {
    val currentSettings = storage.settings[latestIndex.value]
    return updateSettings(currentSettings.copy(threadName = threadName))
}

/**
 * Updates the active plan and completes its pending `update_plan` call.
 *
 * [completed] must match a pending `update_plan` call. The settings update and
 * tool completion are separate AgentState operations.
 */
public suspend fun KodexAgentState.appendPlanUpdate(completed: StablePlanUpdate): Int {
    val pending = (state.value as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.firstOrNull { event -> event.callId == completed.callId }
        ?: throw IllegalArgumentException(
            "Tool output does not match a pending call id: ${completed.callId}",
        )
    require(pending is PendingPlanUpdate && pending.arguments == completed.arguments) {
        "Plan updates can complete only a pending update_plan function call."
    }

    val currentSettings = storage.settings[latestIndex.value]
    updateSettings(currentSettings.copy(plan = completed.arguments))
    return completeToolCall(completed)
}

/**
 * Requests an explicit user-initiated server-side context compaction.
 */
public suspend fun KodexAgentState.forcedCompact(): Int =
    compact(
        trigger = CompactionTrigger.Manual,
        reason = CompactionReason.UserRequested,
        phase = CompactionPhase.StandaloneTurn,
    )

/**
 * Completes all currently pending local tool calls as failed with
 * `user interrupt`.
 *
 * Every individual transition is performed by [KodexAgentState.completeToolCall],
 * preserving its existing validation and atomic stable/unstable history update.
 */
public suspend fun KodexAgentState.clearPending(): Int {
    var index = latestIndex.value
    while (true) {
        val pending = state.value as? KodexAgentStateValue.ToolPending ?: return index
        index = completeToolCall(pending.events.first().toFailedToolEvent(UserInterruptToolMessage))
    }
}

private const val UserInterruptToolMessage: String = "user interrupt"
