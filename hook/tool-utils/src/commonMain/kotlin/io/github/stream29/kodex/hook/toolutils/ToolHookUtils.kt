package io.github.stream29.kodex.hook.toolutils

import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.hook.contract.toHookTurnContext
import io.github.stream29.kodex.hook.contract.tool.HookToolInvocation
import io.github.stream29.kodex.hook.contract.tool.PostToolUseRequest
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.tool.ToolHooks
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Executes PreToolUse hooks before [pending].
 *
 * [storage] is read only to project the latest persisted Hook context. Calls
 * that intentionally do not participate in Tool Hooks are allowed unchanged.
 * PreToolUse may block [pending], but cannot replace or modify it.
 */
public suspend fun ToolHooks.runPreToolUse(
    storage: KodexAgentStorage,
    pending: PendingToolEvent,
): PreToolUseResult {
    val call = pending.projectedToolCallOrNull() ?: return PreToolUseResult.Continue
    val descriptor = call.toToolHookDescriptor()
        ?: return PreToolUseResult.Continue
    val invocation = descriptor.toInvocation(storage, call.callId)
    return onPreToolUse(invocation)
}

/**
 * Executes PostToolUse hooks after a successful [completed] tool event.
 *
 * PostToolUse is observation-only: it cannot replace [completed] or change its
 * success state. Failed outputs and calls that do not participate in Tool Hooks
 * are ignored. [storage] is only read to project the latest persisted context.
 */
public suspend fun ToolHooks.runPostToolUse(
    storage: KodexAgentStorage,
    completed: StableCleanEvent.CompletedTool,
) {
    val (call, output) = completed.projectedToolCallOutputOrNull() ?: return
    if (!output.isSuccessfulForPostToolUse()) return
    val descriptor = call.toToolHookDescriptor() ?: return
    val response = output.toHookResponse() ?: return
    onPostToolUse(
        PostToolUseRequest(
            invocation = descriptor.toInvocation(storage, call.callId),
            response = response,
        ),
    )
}

private fun PendingToolEvent.projectedToolCallOrNull(): ResponseItem.ToolCall? =
    toResponseHistoryItems().filterIsInstance<ResponseItem.ToolCall>().singleOrNull()

private fun StableCleanEvent.CompletedTool.projectedToolCallOutputOrNull(): Pair<
    ResponseItem.ToolCall,
    ResponseItem.ToolCallOutput,
>? {
    val items = toResponseHistoryItems()
    val call = items.filterIsInstance<ResponseItem.ToolCall>().singleOrNull() ?: return null
    val output = items.filterIsInstance<ResponseItem.ToolCallOutput>().singleOrNull() ?: return null
    check(call.callId == output.callId) {
        "Completed tool event projected a call and output with different call ids."
    }
    return call to output
}

private data class ToolHookDescriptor(
    val toolName: String,
    val input: JsonElement,
)

private suspend fun ToolHookDescriptor.toInvocation(
    storage: KodexAgentStorage,
    toolUseId: String,
): HookToolInvocation {
    val settings = storage.settings.latestValue()
    return HookToolInvocation(
        context = settings.toHookTurnContext(storage.id),
        toolName = toolName,
        toolUseId = toolUseId,
        input = input,
    )
}

/**
 * @return The Hook-facing call projection, or `null` when this call does not
 * participate in Tool Hooks.
 */
private fun ResponseItem.ToolCall.toToolHookDescriptor(): ToolHookDescriptor? = when (this) {
    is ResponseItem.ClientToolSearchCall -> null
    is ResponseItem.CustomToolCall -> ToolHookDescriptor(
        toolName = namespace?.let { value -> "${value}__$name" } ?: name,
        input = JsonPrimitive(input),
    )

    is ResponseItem.FunctionCall -> ToolHookDescriptor(
        toolName = namespace?.let { value -> "${value}__$name" } ?: name,
        input = arguments.toHookInput(),
    )
}

private fun ResponseItem.ToolCallOutput.isSuccessfulForPostToolUse(): Boolean =
    when (this) {
        is ResponseItem.FunctionCallOutput -> output.success != false
        is ResponseItem.CustomToolCallOutput -> output.success != false
        is ResponseItem.McpToolCallOutput -> output.isError != true
        is ResponseItem.ClientToolSearchOutput -> true
    }

/**
 * @return The serializer-defined model-visible Hook response, or `null` when
 * this output does not participate in Tool Hooks.
 */
private fun ResponseItem.ToolCallOutput.toHookResponse(): JsonElement? = when (this) {
    is ResponseItem.FunctionCallOutput -> OpenAiJsonCodec.encodeToJsonElement(
        FunctionCallOutputPayload.serializer(),
        output,
    )

    is ResponseItem.CustomToolCallOutput -> OpenAiJsonCodec.encodeToJsonElement(
        FunctionCallOutputPayload.serializer(),
        output,
    )

    is ResponseItem.McpToolCallOutput -> OpenAiJsonCodec.encodeToJsonElement(
        CallToolResult.serializer(),
        output,
    )

    is ResponseItem.ClientToolSearchOutput -> null
}

private fun String.toHookInput(): JsonElement {
    if (isBlank()) return JsonObject(emptyMap())
    return runCatching { OpenAiJsonCodec.parseToJsonElement(this) }
        .getOrElse { JsonPrimitive(this) }
}
