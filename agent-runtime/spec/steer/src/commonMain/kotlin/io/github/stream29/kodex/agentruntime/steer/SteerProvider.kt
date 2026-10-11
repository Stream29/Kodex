package io.github.stream29.kodex.agentruntime.steer

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent

/**
 * Host-owned observation and atomic claim boundary for pending steer input.
 *
 * Responding runtimes observe input through [waitForSteer] and claim it through
 * [take] only after their own operation reaches a legal insertion boundary.
 * Both operations refer to the same host queue. This contract does not decide
 * request cancellation, tool execution, or runtime continuation.
 */
public interface SteerProvider {
    /**
     * Returns when pending input is observed, without removing or reserving it.
     *
     * Already pending input satisfies the wait; otherwise it suspends until a
     * nonempty queue is observed or the caller is cancelled. This is a state
     * observation, not a log of every enqueue. Input retracted or claimed
     * before observation need not wake this waiter.
     *
     * Return does not guarantee that the queue is still nonempty, that [take]
     * will return input, or that history permits insertion. The caller must
     * finish its own necessary cleanup and recheck state before claiming.
     *
     * @throws kotlinx.coroutines.CancellationException when the caller cancels
     * the wait; cancellation does not change the queue.
     */
    public suspend fun waitForSteer(): Unit

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
     * @throws kotlinx.coroutines.CancellationException when the caller is
     * cancelled before the atomic claim; a completed claim is not retracted.
     */
    public suspend fun take(): List<StableIndexEvent.Steerable>
}
