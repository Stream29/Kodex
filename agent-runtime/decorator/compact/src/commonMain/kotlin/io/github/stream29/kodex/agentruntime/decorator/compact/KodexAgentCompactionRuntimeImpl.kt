@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.steer.SteerProvider
import io.github.stream29.kodex.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.OpenAiFailureDiagnosticCarrier
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * Default [KodexAgentCompactionRuntime] backed by the supplied Agent state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public actual class KodexAgentCompactionRuntimeImpl actual constructor(
    private val delegate: KodexAgentState,
    private val modelCatalog: OpenAiModelCatalogStore,
    private val logger: KLogger,
    private val steerProvider: SteerProvider,
) : KodexAgentCompactionRuntime, KodexAgentState by delegate {

    public actual override suspend fun resume() {
        if (state.value is KodexAgentStateValue.ToolPending) return
        deliverPendingSteer()

        if (shouldAutoCompact()) {
            compactForContextLimit(CompactionPhase.PreTurn)
        }

        var responseRetryCount = 0
        while (true) {
            // Also catches input queued during automatic compaction.
            deliverPendingSteer()
            logger.info { "Agent response request started." }
            val finishReason = try {
                requestResponseWithSteer().also { reason ->
                    if (reason == null) {
                        logger.info { "Agent response request preempted for steer." }
                    } else {
                        logger.info { "Agent response request finished (reason=$reason)." }
                    }
                }
            } catch (cancellation: CancellationException) {
                logger.info { "Agent response request cancelled." }
                throw cancellation
            } catch (failure: Throwable) {
                logger.error {
                    "Agent response request failed " +
                        "(type=${failure::class.simpleName}, " +
                        "request=ordinary, retries=$responseRetryCount, " +
                        "diagnostic=${(failure as? OpenAiFailureDiagnosticCarrier)?.failureDiagnostic ?: delegate.lastResponseFailure})."
                }
                throw failure
            }

            if (state.value is KodexAgentStateValue.ToolPending) return

            when (finishReason) {
                RequestFinish.Continue -> {
                    responseRetryCount = 0
                    logger.info { "Agent response continuation requested." }
                }

                RequestFinish.Retryable -> {
                    if (responseRetryCount >= CompactionRetryPolicy.maxRetries) {
                        val failure = AgentResponseRetryLimitExceededException(CompactionRetryPolicy.maxRetries)
                        logger.error(failure) {
                            "Agent response retry limit exceeded (request=ordinary, " +
                                "retries=$responseRetryCount, maxRetries=${CompactionRetryPolicy.maxRetries}, " +
                                "diagnostic=${delegate.lastResponseFailure})."
                        }
                        throw failure
                    }
                    responseRetryCount += 1
                    logger.info {
                        "Agent response retry requested " +
                            "(retry=$responseRetryCount/${CompactionRetryPolicy.maxRetries})."
                    }
                }

                // A finished response followed by new input previously started
                // another resume invocation with a fresh retry budget.
                RequestFinish.Finish -> responseRetryCount = 0
                null -> Unit
            }

            val deliveredSteer = deliverPendingSteer()
            if (finishReason == RequestFinish.Finish && !deliveredSteer) return

            if (shouldAutoCompact()) {
                compactForContextLimit(CompactionPhase.MidTurn)
            }
        }
    }

    /** Null denotes only our own request preemption, not failure or retry. */
    private suspend fun requestResponseWithSteer(): RequestFinish? = coroutineScope {
        val response = async { requestResponseApi() }
        val waitingSteer = async { steerProvider.waitForSteer() }
        try {
            select {
                response.onAwait { it }
                waitingSteer.onAwait {
                    response.cancel(SteerRequestCancellation())
                    try {
                        // If completion won the race, keep its actual result.
                        response.await()
                    } catch (cancellation: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (cancellation !is SteerRequestCancellation) throw cancellation
                        null
                    }
                }
            }
        } finally {
            response.cancel()
            waitingSteer.cancel()
        }
        // coroutineScope joins both children, including AgentState recovery,
        // before the caller can inspect state or claim any input.
    }

    private suspend fun deliverPendingSteer(): Boolean {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!state.value.canAppendUserMessage) return false
        val delivered = withContext(NonCancellable) {
            // Cancellation before claim leaves ownership with the queue.
            caller.ensureActive()
            val inputs = steerProvider.take()
            if (inputs.isEmpty()) return@withContext false
            logger.info { "Agent steer delivery started (${inputs.size} input(s))." }
            // Once claimed, finish persistence even if the outer turn is stopped.
            try {
                delegate.injectHistory(inputs)
            } catch (cancellation: CancellationException) {
                logger.info { "Agent steer delivery cancelled." }
                throw cancellation
            } catch (failure: Throwable) {
                logger.error(failure) { "Agent steer delivery failed." }
                throw failure
            }
            logger.info { "Agent steer delivery completed (${inputs.size} input(s))." }
            true
        }
        caller.ensureActive()
        return delivered
    }

    actual override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int {
        logger.info {
            "Agent compaction started (trigger=$trigger, reason=$reason, phase=$phase)."
        }
        return try {
            val index = delegate.compact(trigger, reason, phase)
            index.also {
                logger.info {
                    "Agent compaction completed (trigger=$trigger, reason=$reason, phase=$phase)."
                }
            }
        } catch (cancellation: CancellationException) {
            logger.info {
                "Agent compaction cancelled (trigger=$trigger, reason=$reason, phase=$phase)."
            }
            throw cancellation
        } catch (failure: Throwable) {
            val diagnostic = failure as? OpenAiFailureDiagnosticCarrier
            logger.error {
                "Agent compaction failed (trigger=$trigger, reason=$reason, phase=$phase, " +
                    "request=remote_compaction_v2, retries=${diagnostic?.retries}, " +
                    "operation_id=${diagnostic?.operationId}, diagnostic=${diagnostic?.failureDiagnostic})."
            }
            throw failure
        }
    }

    private suspend fun compactForContextLimit(phase: CompactionPhase) {
        compact(
            trigger = CompactionTrigger.Auto,
            reason = CompactionReason.ContextLimit,
            phase = phase,
        )
    }

    private suspend fun shouldAutoCompact(): Boolean {
        return tokensUntilCompaction(modelCatalog) == 0L
    }
}

/**
 * Adds automatic compaction, server-requested continuation, and bounded
 * response retry to this state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public actual fun KodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
    steerProvider: SteerProvider,
): KodexAgentCompactionRuntime =
    KodexAgentCompactionRuntimeImpl(
        delegate = this,
        modelCatalog = modelCatalog,
        logger = logger,
        steerProvider = steerProvider,
    )

private class SteerRequestCancellation : CancellationException("Preempted by pending steer.")
