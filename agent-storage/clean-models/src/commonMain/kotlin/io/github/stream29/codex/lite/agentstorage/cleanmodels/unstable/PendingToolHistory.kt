package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal val PendingToolJson: Json = Json

internal fun pendingFunctionCall(
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

internal fun pendingFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
    name: String,
    namespace: String? = null,
    arguments: JsonElement,
): ResponseItem.FunctionCall =
    pendingFunctionCall(
        callId = callId,
        itemId = itemId,
        name = name,
        namespace = namespace,
        arguments = PendingToolJson.encodeToString(JsonElement.serializer(), arguments),
    )

internal fun <Arguments> pendingFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
    name: String,
    namespace: String? = null,
    serializer: SerializationStrategy<Arguments>,
    arguments: Arguments,
): ResponseItem.FunctionCall =
    pendingFunctionCall(
        callId = callId,
        itemId = itemId,
        name = name,
        namespace = namespace,
        arguments = PendingToolJson.encodeToString(serializer, arguments),
    )

internal fun <Value> pendingJsonElement(
    serializer: SerializationStrategy<Value>,
    value: Value,
): JsonElement =
    PendingToolJson.encodeToJsonElement(serializer, value)
