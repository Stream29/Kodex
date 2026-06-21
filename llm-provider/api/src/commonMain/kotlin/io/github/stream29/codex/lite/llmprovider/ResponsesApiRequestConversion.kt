package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.json.Json

internal fun LlmResponseRequest.toResponsesApiRequest(json: Json): LlmResponseRequest =
    copy(input = input.map { it.toResponsesApiItem(json) })

internal fun LlmCompactionRequest.toResponsesApiRequest(json: Json): LlmCompactionRequest =
    copy(input = input.map { it.toResponsesApiItem(json) })

private fun LlmResponseItem.toResponsesApiItem(json: Json): LlmResponseItem =
    when (this) {
        is LlmResponseItem.McpToolCallOutput -> LlmResponseItem.FunctionCallOutput(
            callId = callId,
            output = output.toFunctionCallOutputPayload(json),
        )

        else -> this
    }
