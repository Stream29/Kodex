@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * Inner runtime layer for Responses sampling and context compaction.
 *
 * It delegates atomic state operations to the same underlying [KodexAgentState].
 * It does not execute pending tools; a pending tool event ends [resume] at the
 * observable state boundary for an outer layer to handle.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public expect class KodexAgentCompactionRuntime(
    delegate: KodexAgentState,
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
) : ResumableAgentLayer {
    override val coroutineContext: CoroutineContext
    override val state: StateFlow<KodexAgentStateValue>
    override val latestIndex: StateFlow<Int>
    override val storage: KodexAgentStorage
    override suspend fun <T> modify(block: suspend (MutableKodexAgentStorage) -> T): T
    override suspend fun requestResponseApi(): RequestFinish
    override suspend fun steer(events: List<StableIndexEvent.Steerable>): Int
    override suspend fun consumeSteer(): List<StableIndexEvent.Steerable>
    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int
    override suspend fun appendUserMessage(content: List<ContentItem>): Int
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int
    override suspend fun updateSettings(settings: KodexAgentSettings): Int
    override suspend fun compareAndSetSettings(expect: KodexAgentSettings, update: KodexAgentSettings): Boolean

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
     * Persisted steer is consumed via [consumeSteer] only when state permits
     * another user message: before context preparation, before sampling, and
     * at restored response boundaries.
     *
     * During sampling, observing a non-empty persisted steer queue via
     * [latestIndex] preempts only the current request. Its state cleanup
     * completes before steer is consumed: completed output remains persisted,
     * uncommitted deltas are discarded, and a pending tool call leaves steer
     * queued for a later legal boundary. Preemption is not a failed response
     * retry or an outer-turn Stop. A finished response with newly delivered
     * input continues sampling. Automatic compaction is not preempted.
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

/**
 * Adds automatic compaction, server-requested continuation, and bounded
 * response retry to this state.
 */
public fun KodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
): KodexAgentCompactionRuntime = KodexAgentCompactionRuntime(
    delegate = this,
    modelCatalog = modelCatalog,
    logger = logger,
)
