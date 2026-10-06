package io.github.stream29.kodex.agentstate.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.agentstate.impl.KodexAgentStateInvalidTransitionException
import kotlinx.coroutines.CancellationException

/**
 * Replaces only the thread name in the latest settings snapshot.
 * The read and unconditional update are separate operations; use State CAS
 * instead when conflict detection is required.
 *
 * @throws CancellationException when storage reading or updating is cancelled.
 * @throws IllegalArgumentException when no initial settings can be updated.
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
 * Completion failure does not roll back an accepted settings update.
 *
 * @throws IllegalArgumentException when the pending id or plan arguments differ.
 * @throws KodexAgentStateInvalidTransitionException when completion conflicts
 * with another operation after settings were updated.
 * @throws CancellationException when either operation is cancelled.
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
 *
 * @throws KodexAgentStateInvalidTransitionException when compaction is not legal.
 * @throws IllegalStateException when ownership or initial storage is absent.
 * @throws CancellationException when the delegated operation is cancelled.
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
 * Already completed calls remain committed if a later completion fails.
 *
 * @throws KodexAgentStateInvalidTransitionException when observed pending state
 * is replaced before its completion can be admitted.
 * @throws IllegalArgumentException when the observed call no longer matches.
 * @throws CancellationException when a completion is cancelled.
 */
public suspend fun KodexAgentState.clearPending(): Int {
    var index = latestIndex.value
    while (true) {
        val pending = state.value as? KodexAgentStateValue.ToolPending ?: return index
        index = completeToolCall(pending.events.first().toFailedToolEvent(UserInterruptToolMessage))
    }
}

private const val UserInterruptToolMessage: String = "user interrupt"
