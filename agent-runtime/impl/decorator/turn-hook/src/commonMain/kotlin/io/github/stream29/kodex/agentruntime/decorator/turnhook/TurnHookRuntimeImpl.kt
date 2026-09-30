package io.github.stream29.kodex.agentruntime.decorator.turnhook

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.hook.contract.toHookTurnContext
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.TurnHooks
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

/**
 * Default [TurnHookRuntime] over the supplied Hook collection.
 */
public class TurnHookRuntimeImpl internal constructor(
    private val delegate: ResumableAgentLayer,
    private val hooks: TurnHooks,
    private val logger: KLogger,
) : TurnHookRuntime, KodexAgentState by delegate {
    override suspend fun resume() {
        val settings = storage.settings.latestValue()
        val context = settings.toHookTurnContext(
            uri = storage.uri,
            turnId = settings.turnId,
        )
        currentUserPromptTextOrNull()?.let { prompt ->
            when (
                val result = logger.runHook("UserPromptSubmit") {
                    hooks.onUserPromptSubmit(
                        UserPromptSubmitRequest(
                            context = context,
                            prompt = prompt,
                        ),
                    )
                }
            ) {
                is UserPromptSubmitResult.Continue -> {
                    persistAdditionalContexts(result.additionalContexts)
                    if (result.additionalContexts.isNotEmpty()) {
                        logger.info {
                            "UserPromptSubmit hook supplied " +
                                "${result.additionalContexts.size} additional context(s)."
                        }
                    }
                }

                is UserPromptSubmitResult.Stop -> {
                    persistAdditionalContexts(result.additionalContexts)
                    logger.info { "Agent turn stopped by UserPromptSubmit hook." }
                    return
                }
            }
        }

        var stopHookActive = false
        var lastAssistantMessage: String? = null

        while (true) {
            val historyStartIndex = latestIndex.value
            delegate.resume()

            val currentState = state.value
            val pendingHostInteractions = TurnHookProjection.pendingHostInteractions(currentState)
            if (currentState != KodexAgentStateValue.AssistantMessage &&
                pendingHostInteractions.isEmpty()
            ) {
                return
            }
            latestAssistantMessageSince(historyStartIndex)?.let { text ->
                lastAssistantMessage = text
            }

            when (
                val result = logger.runHook("Stop") {
                    hooks.onStop(
                        StopRequest(
                            context = context,
                            stopHookActive = stopHookActive,
                            lastAssistantMessage =
                                lastAssistantMessage
                                    ?: pendingHostInteractions.firstOrNull()?.let(TurnHookProjection::stopHookMessage),
                        ),
                    )
                }
            ) {
                // Keep the object case separate: Kotlin/Native may otherwise cast Finish to Stop
                // while lowering this combined sealed-type branch.
                StopResult.Finish -> return

                is StopResult.Stop -> {
                    pendingHostInteractions.forEach { pending ->
                        completeToolCall(pending.toFailedToolEvent(TurnHookProjection.stopHookFailureMessage(pending)))
                    }
                    logger.info { "Agent turn stopped by Stop hook." }
                    return
                }

                is StopResult.Continue -> {
                    if (result.fragments.isEmpty()) return
                    pendingHostInteractions.forEach { pending ->
                        completeToolCall(pending.toFailedToolEvent(TurnHookProjection.stopHookFailureMessage(pending)))
                    }
                    logger.info {
                        "Stop hook requested Agent continuation with " +
                            "${result.fragments.size} fragment(s)."
                    }
                    injectHistory(listOf(TurnHookProjection.toHookPromptEvent(result.fragments)))
                    stopHookActive = true
                }
            }
        }
    }

    private suspend fun persistAdditionalContexts(contexts: List<String>) {
        if (contexts.isEmpty()) return
        injectHistory(
            contexts.map { context ->
                StableDeveloperMessage(
                    content = listOf(ContentItem.InputText(context)),
                )
            },
        )
    }

    /**
     * @return Text from the latest user message in the current trailing
     * user/developer history block, or `null` when this resume was not
     * initiated from persisted user input.
     */
    private suspend fun currentUserPromptTextOrNull(): String? {
        if (state.value != KodexAgentStateValue.UserMessage) return null
        val message = storage.index
            .indexesIn(0..latestIndex.value)
            .asReversed()
            .mapNotNull { index -> storage.index.getExact(index) as? StableIndexEvent }
            .takeWhile { event ->
                event is StableUserMessage ||
                    event is StableDeveloperMessage
            }
            .firstOrNull { event -> event is StableUserMessage }
            as? StableUserMessage
        return message?.content?.let(TurnHookProjection::userPromptText)
    }

    private suspend fun latestAssistantMessageSince(historyStartIndex: Int): String? =
        storage.index
            .indexesIn((historyStartIndex + 1)..latestIndex.value)
            .asReversed()
            .map { index ->
                (storage.index.getExact(index) as? StableAssistantMessage)
                    ?.let(TurnHookProjection::assistantOutputText)
            }
            .firstOrNull { text -> text != null }
}

/**
 * Adds user-prompt and stop Hooks to this outer runtime.
 *
 * @param logger Agent-scoped logger for turn Hook execution.
 */
public fun ResumableAgentLayer.turnHookRuntime(
    hooks: TurnHooks,
    logger: KLogger,
): TurnHookRuntime = TurnHookRuntimeImpl(this, hooks, logger)

private suspend inline fun <Result> KLogger.runHook(
    name: String,
    block: suspend () -> Result,
): Result {
    info { "$name hook started." }
    return try {
        block().also {
            info { "$name hook completed." }
        }
    } catch (cancellation: CancellationException) {
        info { "$name hook cancelled." }
        throw cancellation
    } catch (failure: Throwable) {
        error(failure) { "$name hook failed." }
        throw failure
    }
}
