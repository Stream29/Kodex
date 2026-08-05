package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.kodex.hook.contract.compaction.CompactionHooks
import io.github.stream29.kodex.hook.contract.compaction.HookCompactionTrigger
import io.github.stream29.kodex.hook.contract.toHookTurnContext
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

/**
 * Basic runtime with no environment-side effects.
 *
 * It handles token-limit compaction and server-requested continuation before
 * returning control. A pending tool call ends this flow so a higher runtime
 * can execute the tool through the inherited atomic state API.
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

    public override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        if (state.value is KodexAgentStateValue.ToolPending) {
            return@channelFlow
        }

        if (shouldAutoCompact()) {
            compactForContextLimit(CompactionPhase.PreTurn)
        }

        while (true) {
            var needsFollowUp = false
            logger.info { "Agent response request started." }
            try {
                requestResponseApi().collect { event ->
                    if (event is ResponsesStreamEvent.Completed && event.response.endTurn == false) {
                        needsFollowUp = true
                    }
                    send(event)
                }
                logger.info { "Agent response request finished." }
            } catch (cancellation: CancellationException) {
                logger.info { "Agent response request cancelled." }
                throw cancellation
            } catch (failure: Throwable) {
                logger.error(failure) { "Agent response request failed." }
                throw failure
            }

            if (state.value is KodexAgentStateValue.ToolPending || !needsFollowUp) {
                return@channelFlow
            }

            logger.info { "Agent response continuation requested." }
            if (shouldAutoCompact()) {
                compactForContextLimit(CompactionPhase.MidTurn)
            }
        }
    }.buffer(Channel.UNLIMITED)

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
                val context = storage.settings[latestIndex.value].toHookTurnContext(storage.id)
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
 * Adds automatic compaction and server-requested continuation to this state.
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
