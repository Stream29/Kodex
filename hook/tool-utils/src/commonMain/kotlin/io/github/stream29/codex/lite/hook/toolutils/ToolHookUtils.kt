package io.github.stream29.codex.lite.hook.toolutils

import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.hook.contract.toHookTurnContext
import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation
import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseRequest
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Executes PreToolUse hooks before [call].
 *
 * [storage] is read only to project the latest persisted Hook context. Calls
 * that intentionally do not participate in Tool Hooks are allowed unchanged.
 * PreToolUse may block [call], but cannot replace or modify it.
 */
public suspend fun ToolHooks.runPreToolUse(
    storage: CodexAgentStorage,
    call: ResponseItem.ToolCall,
): PreToolUseResult {
    val descriptor = call.toToolHookDescriptor()
        ?: return PreToolUseResult.Continue
    val invocation = descriptor.toInvocation(storage, call.callId)
    return onPreToolUse(invocation)
}

/**
 * Executes PostToolUse hooks after a successful [output].
 *
 * PostToolUse is observation-only: it cannot replace [output] or change its
 * success state. Failed outputs and calls that do not participate in Tool Hooks
 * are ignored. [storage] is only read to project the latest persisted context.
 */
public suspend fun ToolHooks.runPostToolUse(
    storage: CodexAgentStorage,
    call: ResponseItem.ToolCall,
    output: ResponseItem.ToolCallOutput,
) {
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

/** Projects a PreToolUse rejection into the corresponding model-visible tool output. */
public fun ResponseItem.ToolCall.toHookBlockedOutput(reason: String): ResponseItem.ToolCallOutput =
    when (this) {
        is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(reason).copy(success = false),
        )

        is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(reason).copy(success = false),
        )

        is ResponseItem.ClientToolSearchCall -> error("Client tool search does not run Tool Hooks.")
    }

private data class ToolHookDescriptor(
    val toolName: String,
    val input: JsonElement,
)

private suspend fun ToolHookDescriptor.toInvocation(
    storage: CodexAgentStorage,
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
