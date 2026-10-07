package io.github.stream29.kodex.agentruntime.decorator.steer

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent

/**
 * Host-owned atomic claim boundary for pending steer input.
 *
 * [SteerRuntime] asks [take] for ownership instead of reading and clearing the
 * host's pending StateFlow itself. Implementations coordinate with producers
 * and competing claimants, including interruption, so two claimants cannot
 * receive the same input and a concurrent append cannot be overwritten. The
 * queue's compare-and-set mechanism stays behind this interface.
 */
public fun interface SteerProvider {
    /**
     * Atomically removes and returns the currently pending batch in order.
     *
     * The claim is linearizable with appends and other claims: an input appended
     * concurrently is either included in this batch or remains for a later
     * claim. Do not wait for future input; return an empty list when the queue
     * is empty at the claim point. A returned batch belongs only to this
     * caller and must not be returned by another claim.
     *
     * Claiming transfers ownership, not persistence. The runtime checks
     * whether input can be appended before calling [take] and persists a
     * nonempty batch through the AgentState API after it returns.
     *
     * @return the exclusively claimed batch, or an empty list if none is pending.
     */
    public suspend fun take(): List<StableIndexEvent.Steerable>
}
