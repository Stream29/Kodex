package io.github.stream29.kodex.agentstate.contract

import io.github.stream29.kodex.agentstate.impl.KodexAgentStateInvalidTransitionException
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import kotlinx.coroutines.CancellationException

/**
 * Replaces only the thread name in the latest settings snapshot.
 * The read and unconditional update are separate operations; use State CAS
 * instead when conflict detection is required.
 *
 * @throws CancellationException when storage reading or updating is cancelled.
 * @throws IllegalArgumentException when no initial settings can be updated.
 */
public expect suspend fun KodexAgentState.updateThreadName(threadName: String): Int

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
public expect suspend fun KodexAgentState.appendPlanUpdate(completed: StablePlanUpdate): Int

/**
 * Requests an explicit user-initiated server-side context compaction.
 *
 * @throws KodexAgentStateInvalidTransitionException when compaction is not legal.
 * @throws IllegalStateException when ownership or initial storage is absent.
 * @throws CancellationException when the delegated operation is cancelled.
 */
public expect suspend fun KodexAgentState.forcedCompact(): Int

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
public expect suspend fun KodexAgentState.clearPending(): Int
