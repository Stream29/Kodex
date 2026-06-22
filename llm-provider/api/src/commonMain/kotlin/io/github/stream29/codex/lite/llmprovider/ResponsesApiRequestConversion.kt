package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.json.Json

internal fun ResponsesApiRequest.toResponsesApiRequest(json: Json): ResponsesApiRequest =
    copy(input = input.map { it.toResponsesApiItem(json) })

internal fun CompactionInput.toResponsesApiRequest(json: Json): CompactionInput =
    copy(input = input.map { it.toResponsesApiItem(json) })

private fun ResponseItem.toResponsesApiItem(json: Json): ResponseItem =
    when (this) {
        is ResponseItem.McpToolCallOutput -> ResponseItem.FunctionCallOutput(
            callId = callId,
            output = output.toFunctionCallOutputPayload(json),
        )

        else -> this
    }
