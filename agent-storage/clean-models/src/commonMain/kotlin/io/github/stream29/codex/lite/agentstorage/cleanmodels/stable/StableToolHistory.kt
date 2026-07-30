package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal val StableToolJson: Json = Json {
    explicitNulls = false
}

internal fun stableFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
    name: String,
    namespace: String? = null,
    arguments: String,
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        id = itemId,
        callId = callId,
        name = name,
        namespace = namespace,
        arguments = arguments,
    )

internal fun <Arguments> stableFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
    name: String,
    namespace: String? = null,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
): ResponseItem.FunctionCall =
    stableFunctionCall(
        callId = callId,
        itemId = itemId,
        name = name,
        namespace = namespace,
        arguments = StableToolJson.encodeToString(serializer, arguments),
    )

internal fun stableFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
    name: String,
    namespace: String? = null,
    arguments: JsonElement,
): ResponseItem.FunctionCall =
    stableFunctionCall(
        callId = callId,
        itemId = itemId,
        name = name,
        namespace = namespace,
        arguments = StableToolJson.encodeToString(JsonElement.serializer(), arguments),
    )

internal fun <Value> stableJsonElement(
    serializer: SerializationStrategy<Value>,
    value: Value,
): JsonElement =
    StableToolJson.encodeToJsonElement(serializer, value)

internal fun stableFunctionOutput(
    callId: String,
    output: FunctionCallOutputPayload,
): ResponseItem.FunctionCallOutput =
    ResponseItem.FunctionCallOutput(
        callId = callId,
        output = output,
    )

internal fun stableTextOutput(
    callId: String,
    text: String,
    success: Boolean?,
): ResponseItem.FunctionCallOutput =
    stableFunctionOutput(
        callId = callId,
        output = FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text(text),
            success = success,
        ),
    )

internal fun <Result> stableJsonOutput(
    callId: String,
    serializer: SerializationStrategy<Result>,
    result: Result,
    success: Boolean?,
): ResponseItem.FunctionCallOutput =
    stableTextOutput(
        callId = callId,
        text = StableToolJson.encodeToString(serializer, result),
        success = success,
    )
