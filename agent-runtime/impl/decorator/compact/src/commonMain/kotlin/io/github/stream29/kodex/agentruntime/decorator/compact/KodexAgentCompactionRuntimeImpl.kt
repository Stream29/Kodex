package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import kotlinx.coroutines.CancellationException

/**
 * Default [KodexAgentCompactionRuntime] backed by the supplied Agent state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public class KodexAgentCompactionRuntimeImpl(
    private val delegate: KodexAgentState,
    private val modelCatalog: OpenAiModelCatalogStore,
    private val logger: KLogger,
) : KodexAgentCompactionRuntime, KodexAgentState by delegate {

    public override suspend fun resume() {
        if (state.value is KodexAgentStateValue.ToolPending) return

        if (shouldAutoCompact()) {
            compactForContextLimit(CompactionPhase.PreTurn)
        }

        var responseRetryCount = 0
        while (true) {
            logger.info { "Agent response request started." }
            val finishReason = try {
                requestResponseApi().also { reason ->
                    logger.info { "Agent response request finished (reason=$reason)." }
                }
            } catch (cancellation: CancellationException) {
                logger.info { "Agent response request cancelled." }
                throw cancellation
            } catch (failure: Throwable) {
                logger.error(failure) {
                    "Agent response request failed " +
                        "(type=${failure::class.simpleName}, message=${failure.message ?: "unknown"})."
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
                            "Agent response retry limit exceeded (maxRetries=${CompactionRetryPolicy.maxRetries})."
                        }
                        throw failure
                    }
                    responseRetryCount += 1
                    logger.info {
                        "Agent response retry requested " +
                            "(retry=$responseRetryCount/${CompactionRetryPolicy.maxRetries})."
                    }
                }

                RequestFinish.Finish -> return
            }

            if (shouldAutoCompact()) {
                compactForContextLimit(CompactionPhase.MidTurn)
            }
        }
    }

    override suspend fun compact(
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
            logger.error(failure) {
                "Agent compaction failed (trigger=$trigger, reason=$reason, phase=$phase)."
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
public fun KodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
): KodexAgentCompactionRuntime =
    KodexAgentCompactionRuntimeImpl(
        delegate = this,
        modelCatalog = modelCatalog,
        logger = logger,
    )
