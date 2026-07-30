package io.github.stream29.kodex.agentruntime.decorator.turnhook

import io.github.stream29.kodex.agentcontext.promptdsl.promptXml
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.indexesDescending
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
import io.github.stream29.kodex.hook.contract.toHookTurnContext
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.TurnHooks
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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
) : ResumableAgentLayer by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        val context = storage.settings.latestValue().toHookTurnContext(storage.id)
        currentUserPromptTextOrNull()?.let { prompt ->
            when (
                val result = hooks.onUserPromptSubmit(
                    UserPromptSubmitRequest(
                        context = context,
                        prompt = prompt,
                    ),
                )
            ) {
                is UserPromptSubmitResult.Continue ->
                    persistAdditionalContexts(result.additionalContexts)

                is UserPromptSubmitResult.Stop -> {
                    persistAdditionalContexts(result.additionalContexts)
                    return@channelFlow
                }
            }
        }

        var stopHookActive = false
        var lastAssistantMessage: String? = null

        while (true) {
            val historyStartIndex = latestIndex.value
            var naturalCompletion = false
            delegate.resume().collect { event ->
                when (event) {
                    is ResponsesStreamEvent.Completed -> naturalCompletion = true
                    is ResponsesStreamEvent.Failed,
                    is ResponsesStreamEvent.Incomplete,
                    -> naturalCompletion = false

                    else -> Unit
                }
                send(event)
            }

            if (!naturalCompletion || state.value != KodexAgentStateValue.AssistantMessage) {
                return@channelFlow
            }
            latestAssistantMessageSince(historyStartIndex)?.let { text ->
                lastAssistantMessage = text
            }

            when (
                val result = hooks.onStop(
                    StopRequest(
                        context = context,
                        stopHookActive = stopHookActive,
                        lastAssistantMessage = lastAssistantMessage,
                    ),
                )
            ) {
                // Keep the object case separate: Kotlin/Native may otherwise cast Finish to Stop
                // while lowering this combined sealed-type branch.
                StopResult.Finish -> return@channelFlow

                is StopResult.Stop -> return@channelFlow

                is StopResult.Continue -> {
                    if (result.fragments.isEmpty()) return@channelFlow
                    injectHistory(listOf(result.fragments.toHookPromptEvent()))
                    stopHookActive = true
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun persistAdditionalContexts(contexts: List<String>) {
        if (contexts.isEmpty()) return
        injectHistory(
            contexts.map { context ->
                StableCleanEvent.DeveloperMessage(
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
        val message = storage.stable
            .indexesDescending(latestIndex.value)
            .map { index -> storage.stable[index] }
            .takeWhile { event ->
                event is StableCleanEvent.UserMessage ||
                    event is StableCleanEvent.DeveloperMessage
            }
            .firstOrNull { event -> event is StableCleanEvent.UserMessage }
            as? StableCleanEvent.UserMessage
        return message?.content?.userPromptText()
    }

    private suspend fun latestAssistantMessageSince(historyStartIndex: Int): String? =
        storage.stable
            .indexesDescending(latestIndex.value)
            .firstOrNull { index ->
                index > historyStartIndex &&
                    storage.stable[index] is StableCleanEvent.AssistantMessage
            }
            ?.let { index ->
                (storage.stable[index] as StableCleanEvent.AssistantMessage).assistantOutputText()
            }
}

/** Adds user-prompt and natural-completion Hooks to this outer runtime. */
public fun ResumableAgentLayer.turnHookRuntime(
    hooks: TurnHooks,
): ResumableAgentLayer = TurnHookRuntime(this, hooks)

private fun List<ContentItem>.userPromptText(): String =
    filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "", transform = ContentItem.InputText::text)

private fun StableCleanEvent.AssistantMessage.assistantOutputText(): String? {
    val output = content.filterIsInstance<ContentItem.OutputText>()
    return output.takeIf(List<ContentItem.OutputText>::isNotEmpty)
        ?.joinToString(separator = "", transform = ContentItem.OutputText::text)
}

private fun List<HookPromptFragment>.toHookPromptEvent(): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(
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
