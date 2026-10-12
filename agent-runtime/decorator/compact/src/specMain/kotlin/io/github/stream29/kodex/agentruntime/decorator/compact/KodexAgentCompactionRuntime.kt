package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger

/**
 * Inner runtime layer for Responses sampling and context compaction.
 *
 * It delegates atomic state operations to the same underlying AgentState.
 * It does not execute pending tools; a pending tool event ends [resume] at the
 * observable state boundary for an outer layer to handle.
 */
public interface KodexAgentCompactionRuntime : ResumableAgentLayer {
    /**
     * Returns immediately if a tool call is already pending. Otherwise,
     * compacts for a reached context limit before the first Responses request
     * and, after a continued or retryable response, before the next request.
     * Sampling stops on a finished response or a pending tool call.
     *
     * A server-requested continuation starts another sample and resets the
     * consecutive retry count. A retryable result may also be sampled again;
     * the retry budget itself belongs to the implementation.
     *
     * Pending Steer is claimed only when state permits another user message,
     * before context preparation, before sampling, and at restored response
     * boundaries. A claimed batch is persisted in order before further work;
     * cancellation after claim does not abandon that delivery. Storage
     * failures propagate without rolling back committed input.
     *
     * During sampling, a non-consuming wait can preempt only the current
     * request. Its state cleanup completes before input is claimed: completed
     * output remains persisted, uncommitted deltas are discarded, and a
     * pending tool call leaves Steer queued for a later legal boundary.
     * Preemption is not a failed response retry or an outer-turn Stop.
     * A finished response with newly delivered input continues sampling.
     * Waiting is scoped to sampling; automatic compaction is not preempted.
     *
     * @throws AgentResponseRetryLimitExceededException when another retryable
     * response arrives after the implementation's retry limit is exhausted.
     * @throws kotlinx.coroutines.CancellationException when the caller cancels
     * execution; request cleanup and any already claimed delivery finish first.
     */
    public override suspend fun resume()

    /**
     * Directly delegates to underlying state compaction, preserving the supplied
     * trigger, reason, phase, committed index and propagated failure or cancellation.
     * No notification command participates in this operation.
     */
    public override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int
}
