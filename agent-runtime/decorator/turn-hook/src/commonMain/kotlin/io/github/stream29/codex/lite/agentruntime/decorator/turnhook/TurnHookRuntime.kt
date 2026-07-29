package io.github.stream29.codex.lite.agentruntime.decorator.turnhook

import io.github.stream29.codex.lite.agentcontext.promptdsl.promptXml
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgent
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.indexesDescending
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.hook.contract.turn.HookPromptFragment
import io.github.stream29.codex.lite.hook.contract.toHookTurnContext
import io.github.stream29.codex.lite.hook.contract.turn.StopRequest
import io.github.stream29.codex.lite.hook.contract.turn.StopResult
import io.github.stream29.codex.lite.hook.contract.turn.TurnHooks
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
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
    private val delegate: ResumableAgent,
    private val hooks: TurnHooks,
) : ResumableAgent by delegate {
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
            var naturalCompletion = false
            delegate.resume().collect { event ->
                when (event) {
                    is ResponsesStreamEvent.OutputItemDone -> {
                        event.item.assistantTextOrNull()?.let { text ->
                            lastAssistantMessage = text
                        }
                    }

                    is ResponsesStreamEvent.Completed -> naturalCompletion = true
                    is ResponsesStreamEvent.Failed,
                    is ResponsesStreamEvent.Incomplete,
                    -> naturalCompletion = false

                    else -> Unit
                }
                send(event)
            }

            if (!naturalCompletion || state.value != CodexAgentStateValue.AssistantMessage) {
                return@channelFlow
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
                StopResult.Finish,
                is StopResult.Stop,
                -> return@channelFlow

                is StopResult.Continue -> {
                    if (result.fragments.isEmpty()) return@channelFlow
                    injectHistory(listOf(result.fragments.toHookPromptMessage()))
                    stopHookActive = true
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun persistAdditionalContexts(contexts: List<String>) {
        if (contexts.isEmpty()) return
        injectHistory(
            contexts.map { context ->
                ResponseItem.Message(
                    role = MessageRole.Developer,
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
        if (state.value != CodexAgentStateValue.UserMessage) return null
        val message = storage.history
            .indexesDescending(latestIndex.value)
            .map { index -> storage.history[index] }
            .takeWhile { item ->
                item is ResponseItem.Message &&
                    (item.role == MessageRole.User || item.role == MessageRole.Developer)
            }
            .firstOrNull { item ->
                item is ResponseItem.Message && item.role == MessageRole.User
            } as? ResponseItem.Message
        return message?.content?.userPromptText()
    }
}

/** Adds user-prompt and natural-completion Hooks to this outer runtime. */
public fun ResumableAgent.turnHookRuntime(
    hooks: TurnHooks,
): ResumableAgent = TurnHookRuntime(this, hooks)

private fun List<ContentItem>.userPromptText(): String =
    filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "\n\n", transform = ContentItem.InputText::text)

private fun ResponseItem.assistantTextOrNull(): String? {
    val message = this as? ResponseItem.Message ?: return null
    if (message.role != MessageRole.Assistant) return null
    val output = message.content.filterIsInstance<ContentItem.OutputText>()
    return output.takeIf(List<ContentItem.OutputText>::isNotEmpty)
        ?.joinToString(separator = "", transform = ContentItem.OutputText::text)
}

private fun List<HookPromptFragment>.toHookPromptMessage(): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
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
