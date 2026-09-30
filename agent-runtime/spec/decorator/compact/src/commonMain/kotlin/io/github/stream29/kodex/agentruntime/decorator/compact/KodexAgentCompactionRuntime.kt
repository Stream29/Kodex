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
 * observable state boundary for an outer layer to handle. Compaction Hooks
 * are optional for both explicit and automatic compaction.
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
     * @throws AgentResponseRetryLimitExceededException when another retryable
     * response arrives after the implementation's retry limit is exhausted.
     */
    public override suspend fun resume()

    /**
     * Runs the underlying state compaction with optional PreCompact and
     * PostCompact Hooks. When Hooks are present, PreCompact runs before the
     * state operation and PostCompact only after it succeeds.
     */
    public override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int
}
