package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CodexResponsesClientMetadata
import io.github.stream29.codex.lite.openai.CodexResponsesMetadata
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun CodexAgentSettings.toResponsesApiRequest(
    input: List<ResponseItem>,
    clientMetadata: CodexResponsesClientMetadata,
    tools: List<ToolSpec>,
): ResponsesApiRequest {
    return ResponsesApiRequest(
        model = model,
        input = input.map(ResponseItem::toResponsesApiInput),
        instructions = instructions,
        store = false,
        previousResponseId = previousResponseId,
        tools = tools,
        toolChoice = toolChoice,
        parallelToolCalls = parallelToolCalls,
        reasoning = when (reasoning.effort) {
            ReasoningEffort.Ultra -> reasoning.copy(effort = ReasoningEffort.Max)
            else -> reasoning
        },
        include = include,
        serviceTier = serviceTier,
        promptCacheKey = promptCacheKey,
        text = text,
        clientMetadata = clientMetadata,
    )
}

private fun ResponseItem.toResponsesApiInput(): ResponseItem =
    when (this) {
        is ResponseItem.McpToolCallOutput -> ResponseItem.FunctionCallOutput(
            callId = callId,
            output = output.toFunctionCallOutputPayload(OpenAiJsonCodec),
        )

        else -> this
    }

internal fun CodexResponsesMetadata.toCodexClientMetadata(): CodexResponsesClientMetadata =
    CodexResponsesClientMetadata(
        installationId = installationId,
        sessionId = sessionId,
        threadId = threadId,
        turnId = turnId,
        windowId = windowId,
        turnMetadata = OpenAiJsonCodec.encodeToString(
            CodexResponsesMetadata.serializer(),
            this,
        ),
    )

/** Projects an arbitrary local storage identity into a stable UUID-shaped provider identity. */
@OptIn(ExperimentalUuidApi::class)
internal fun String.toCodexThreadId(): String {
    val input = (CodexThreadIdentityNamespace + this).encodeToByteArray()
    val high = (input.stableHash(CodexThreadIdentityHighSeed) and 0xffffffffffff0fffUL) or 0x8000UL
    val low = (input.stableHash(CodexThreadIdentityLowSeed) and 0x3fffffffffffffffUL) or 0x8000000000000000UL
    return Uuid.fromULongs(high, low).toString()
}

private fun ByteArray.stableHash(seed: ULong): ULong {
    var hash = seed
    for (byte in this) {
        hash = (hash xor byte.toUByte().toULong()) * FnvPrime
    }
    hash = (hash xor (hash shr 33)) * MurmurMixFirst
    hash = (hash xor (hash shr 33)) * MurmurMixSecond
    return hash xor (hash shr 33)
}

private const val CodexThreadIdentityNamespace: String = "io.github.stream29.codex.lite/openai-thread/v1\u0000"
private const val FnvPrime: ULong = 0x100000001b3UL
private const val CodexThreadIdentityHighSeed: ULong = 0xcbf29ce484222325UL
private const val CodexThreadIdentityLowSeed: ULong = 0x6c62272e07bb0142UL
private const val MurmurMixFirst: ULong = 0xff51afd7ed558ccdUL
private const val MurmurMixSecond: ULong = 0xc4ceb9fe1a85ec53UL
