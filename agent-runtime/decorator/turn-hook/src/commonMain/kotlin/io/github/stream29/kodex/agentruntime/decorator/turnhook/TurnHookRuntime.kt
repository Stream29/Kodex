package io.github.stream29.kodex.agentruntime.decorator.turnhook

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentcontext.promptdsl.promptXml
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
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
 * Runtime layer that owns turn-level Hook control flow for one outer [resume]
 * call. Each Hook boundary projects its identity from the persisted settings
 * snapshot instead of relying on coroutine-local state.
 */
public class TurnHookRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    private val hooks: TurnHooks,
    private val logger: KLogger,
) : ResumableAgentLayer, KodexAgentState by delegate {
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
            val pendingHostInteractions = currentState.pendingHostInteractions()
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
                                    ?: pendingHostInteractions.firstOrNull()?.stopHookMessage(),
                        ),
                    )
                }
            ) {
                // Keep the object case separate: Kotlin/Native may otherwise cast Finish to Stop
                // while lowering this combined sealed-type branch.
                StopResult.Finish -> return

                is StopResult.Stop -> {
                    pendingHostInteractions.forEach { pending ->
                        completeToolCall(pending.toFailedToolEvent(pending.stopHookFailureMessage()))
                    }
                    logger.info { "Agent turn stopped by Stop hook." }
                    return
                }

                is StopResult.Continue -> {
                    if (result.fragments.isEmpty()) return
                    pendingHostInteractions.forEach { pending ->
                        completeToolCall(pending.toFailedToolEvent(pending.stopHookFailureMessage()))
                    }
                    logger.info {
                        "Stop hook requested Agent continuation with " +
                            "${result.fragments.size} fragment(s)."
                    }
                    injectHistory(listOf(result.fragments.toHookPromptEvent()))
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
        return message?.content?.userPromptText()
    }

    private suspend fun latestAssistantMessageSince(historyStartIndex: Int): String? =
        storage.index
            .indexesIn((historyStartIndex + 1)..latestIndex.value)
            .asReversed()
            .map { index ->
                (storage.index.getExact(index) as? StableAssistantMessage)
                    ?.assistantOutputText()
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
): ResumableAgentLayer = TurnHookRuntime(this, hooks, logger)

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

private fun List<ContentItem>.userPromptText(): String =
    filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "", transform = ContentItem.InputText::text)

private fun StableAssistantMessage.assistantOutputText(): String? {
    val output = content.filterIsInstance<ContentItem.OutputText>()
    return output
        .joinToString(separator = "", transform = ContentItem.OutputText::text)
        .takeIf(String::isNotBlank)
}

private fun KodexAgentStateValue.pendingHostInteractions(): List<PendingToolEvent> =
    (this as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.filter { event ->
            event is PendingRequestUserInputToolEvent ||
                event is PendingSuggestSubagentTaskToolEvent
        }
        .orEmpty()

private fun PendingRequestUserInputToolEvent.questionTextOrNull(): String? =
    arguments.questions
        .map { question -> question.question }
        .filter(String::isNotBlank)
        .joinToString(separator = "\n")
        .takeIf(String::isNotEmpty)

private fun PendingSuggestSubagentTaskToolEvent.taskTextOrNull(): String? =
    arguments.tasks
        .joinToString(separator = "\n") { task -> "${task.name}: ${task.prompt}" }
        .takeIf(String::isNotEmpty)

private fun PendingToolEvent.stopHookMessage(): String? =
    when (this) {
        is PendingRequestUserInputToolEvent -> questionTextOrNull()
        is PendingSuggestSubagentTaskToolEvent -> taskTextOrNull()
        else -> null
    }

private fun PendingToolEvent.stopHookFailureMessage(): String =
    when (this) {
        is PendingRequestUserInputToolEvent -> RequestUserInputCancelledByStopHook
        is PendingSuggestSubagentTaskToolEvent -> SuggestSubagentTaskCancelledByStopHook
        else -> error("Unsupported host-owned pending tool: $toolName")
    }

private const val RequestUserInputCancelledByStopHook: String =
    "request_user_input cancelled by Stop hook"

private const val SuggestSubagentTaskCancelledByStopHook: String =
    "suggest_subagent_task cancelled by Stop hook"

private fun List<HookPromptFragment>.toHookPromptEvent(): StableUserMessage =
    StableUserMessage(
        content = map { fragment ->
            ContentItem.InputText(fragment.toHookPromptXml())
        },
    )

private fun HookPromptFragment.toHookPromptXml(): String =
    promptXml(indented = false) {
        tag(
            name = "hook_prompt",
            attributes = mapOf("hook_run_id" to hookRunId),
        ) {
            text(this@toHookPromptXml.text)
        }
    }
