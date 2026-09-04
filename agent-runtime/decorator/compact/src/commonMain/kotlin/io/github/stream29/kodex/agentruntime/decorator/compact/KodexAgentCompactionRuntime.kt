package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.kodex.hook.contract.compaction.CompactionHooks
import io.github.stream29.kodex.hook.contract.compaction.HookCompactionTrigger
import io.github.stream29.kodex.hook.contract.toHookTurnContext
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import kotlinx.coroutines.CancellationException

/**
 * Basic runtime with no environment-side effects.
 *
 * It handles token-limit compaction, server-requested continuation, and bounded
 * response retries before returning control. A pending tool call ends this
 * operation at the observable state boundary so a higher runtime can execute
 * it through the inherited atomic state API.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 * @param compactionHooks Nullable because Hooks are an optional host feature;
 * `null` runs the compaction core without PreCompact or PostCompact.
 */
public class KodexAgentCompactionRuntime(
    private val delegate: KodexAgentState,
    private val modelCatalog: OpenAiModelCatalog,
    private val logger: KLogger,
    private val compactionHooks: CompactionHooks? = null,
) : ResumableAgentLayer, KodexAgentState by delegate {

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
                    if (responseRetryCount >= MaxResponseRetries) {
                        val failure = AgentResponseRetryLimitExceededException(MaxResponseRetries)
                        logger.error(failure) {
                            "Agent response retry limit exceeded (maxRetries=$MaxResponseRetries)."
                        }
                        throw failure
                    }
                    responseRetryCount += 1
                    logger.info {
                        "Agent response retry requested " +
                            "(retry=$responseRetryCount/$MaxResponseRetries)."
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
            val hooks = compactionHooks
            val index = if (hooks == null) {
                delegate.compact(trigger, reason, phase)
            } else {
                val settings = storage.settings[latestIndex.value]
                val context = settings.toHookTurnContext(
                    uri = storage.uri,
                    turnId = settings.turnId,
                )
                val request = CompactionHookRequest(
                    context = context,
                    trigger = trigger.toHookTrigger(),
                )
                hooks.onPreCompact(request)
                delegate.compact(trigger, reason, phase).also {
                    hooks.onPostCompact(request)
                }
            }
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
 * Raised after every retry allowed for one Responses sampling request has also
 * returned a retryable result.
 */
public class AgentResponseRetryLimitExceededException(
    public val maxRetries: Int,
) : IllegalStateException(
    "Agent response request failed after $maxRetries retries.",
)

private const val MaxResponseRetries: Int = 20

/**
 * Adds automatic compaction, server-requested continuation, and bounded
 * response retry to this state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 * @param compactionHooks Nullable because Hooks are optional; `null` disables
 * both compaction Hook boundaries.
 */
public fun KodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalog,
    logger: KLogger,
    compactionHooks: CompactionHooks? = null,
): ResumableAgentLayer =
    KodexAgentCompactionRuntime(
        delegate = this,
        modelCatalog = modelCatalog,
        logger = logger,
        compactionHooks = compactionHooks,
    )

private fun CompactionTrigger.toHookTrigger(): HookCompactionTrigger = when (this) {
    CompactionTrigger.Auto -> HookCompactionTrigger.Auto
    CompactionTrigger.Manual -> HookCompactionTrigger.Manual
}
