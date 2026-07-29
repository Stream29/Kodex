package io.github.stream29.codex.lite.tool.builder

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolCallResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

public val ToolBuilderJson: Json = Json {
    explicitNulls = false
}

public sealed interface JsonToolHandlerResult<out Output> {
    public data class Success<out Output>(
        public val value: Output,
        public val success: Boolean = true,
    ) : JsonToolHandlerResult<Output>

    public data class Failure(
        public val message: String,
    ) : JsonToolHandlerResult<Nothing>
}

public fun <Output> jsonToolSuccess(
    value: Output,
    success: Boolean = true,
): JsonToolHandlerResult<Output> =
    JsonToolHandlerResult.Success(value, success)

public fun jsonToolFailure(message: String): JsonToolHandlerResult<Nothing> =
    JsonToolHandlerResult.Failure(message)

public fun <Input, Output> jsonTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    outputSerializer: SerializationStrategy<Output>,
    json: Json = ToolBuilderJson,
    completedEvent: ((Input, JsonToolHandlerResult<Output>) -> StableCleanEvent.CompletedTool)? = null,
    handler: suspend (Input) -> JsonToolHandlerResult<Output>,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { call, input, arguments ->
        when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure -> {
                failedOutput(result.message) to (
                    completedEvent?.invoke(input, result)
                        ?: StableTextToolEvent(
                            name = call.name,
                            namespace = call.namespace,
                            arguments = arguments,
                            result = result.message,
                            success = false,
                        )
                    )
            }

            is JsonToolHandlerResult.Success -> try {
                val resultJson = json.encodeToJsonElement(outputSerializer, result.value)
                FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text(
                        json.encodeToString(outputSerializer, result.value),
                    ),
                    success = result.success,
                ) to (
                    completedEvent?.invoke(input, result)
                        ?: StableJsonToolEvent(
                            name = call.name,
                            namespace = call.namespace,
                            arguments = arguments,
                            result = resultJson,
                            success = result.success,
                        )
                )
            } catch (error: SerializationException) {
                val message = "failed to serialize tool output: ${error.message}"
                failedOutput(message) to StableTextToolEvent(
                    name = call.name,
                    namespace = call.namespace,
                    arguments = arguments,
                    result = message,
                    success = false,
                )
            }
        }
    }

/**
 * Builds a normal JSON-input function tool whose successful result is sent as
 * raw text instead of JSON-encoded text.
 *
 * The input is still decoded from the function call's JSON arguments. The
 * difference is only the successful function-call output: for example,
 * [jsonTool] with `String.serializer()` sends `"hello"`, while this function
 * sends `hello` unchanged. Use this for Responses API tools whose documented
 * output is model-facing text rather than a JSON value. Failures retain the
 * usual `success = false` text output.
 *
 * This does not support custom-tool payloads such as `apply_patch`.
 */
public fun <Input> textTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    json: Json = ToolBuilderJson,
    completedEvent: ((Input, JsonToolHandlerResult<String>) -> StableCleanEvent.CompletedTool)? = null,
    handler: suspend (Input) -> JsonToolHandlerResult<String>,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { call, input, arguments ->
        when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure -> {
                failedOutput(result.message) to (
                    completedEvent?.invoke(input, result)
                        ?: StableTextToolEvent(
                            name = call.name,
                            namespace = call.namespace,
                            arguments = arguments,
                            result = result.message,
                            success = false,
                        )
                    )
            }

            is JsonToolHandlerResult.Success -> {
                FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text(result.value),
                    success = result.success,
                ) to (
                    completedEvent?.invoke(input, result)
                        ?: StableTextToolEvent(
                            name = call.name,
                            namespace = call.namespace,
                            arguments = arguments,
                            result = result.value,
                            success = result.success,
                        )
                    )
            }
        }
    }

/**
 * Builds a normal JSON-input function tool that returns a protocol-native
 * [FunctionCallOutputPayload].
 *
 * Use this when a successful result contains rich Responses content such as
 * images instead of plain or JSON-encoded text. The `callId` is supplied to the
 * handler for host-owned artifact naming and other call-scoped work.
 *
 * This does not support custom-tool payloads such as `apply_patch`.
 */
public fun <Input> functionOutputTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    json: Json = ToolBuilderJson,
    handler: suspend (
        callId: String,
        input: Input,
    ) -> Pair<FunctionCallOutputPayload, StableCleanEvent.CompletedTool>,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { call, input, _ ->
        handler(call.callId, input)
    }

private class FunctionOutputTool<Input>(
    override val spec: ToolSpec,
    private val inputDeserializer: DeserializationStrategy<Input>,
    private val json: Json,
    private val handler: suspend (
        call: ResponseItem.FunctionCall,
        input: Input,
        arguments: JsonElement,
    ) -> Pair<FunctionCallOutputPayload, StableCleanEvent.CompletedTool>,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(call: ResponseItem.ToolCall): ToolCallResult =
        when (call) {
            is ResponseItem.FunctionCall -> {
                val (output, completed) = handleFunctionCall(call)
                ResponseItem.FunctionCallOutput(
                    callId = call.callId,
                    output = output,
                ) to completed
            }

            is ResponseItem.CustomToolCall -> {
                val message = "JSON tool received custom tool payload"
                ResponseItem.CustomToolCallOutput(
                    callId = call.callId,
                    output = failedOutput(message),
                ) to StableTextToolEvent(
                    name = call.name,
                    namespace = call.namespace,
                    arguments = JsonPrimitive(call.input),
                    result = message,
                    success = false,
                )
            }

            is ResponseItem.ClientToolSearchCall ->
                error("Client tool-search calls are handled by CodexToolRuntime.")
        }

    private suspend fun handleFunctionCall(
        call: ResponseItem.FunctionCall,
    ): Pair<FunctionCallOutputPayload, StableCleanEvent.CompletedTool> {
        val arguments = try {
            json.parseToJsonElement(call.arguments)
        } catch (error: SerializationException) {
            return call.failedResult(
                arguments = JsonPrimitive(call.arguments),
                message = "failed to parse function arguments: ${error.message}",
            )
        }
        val input = try {
            json.decodeFromJsonElement(inputDeserializer, arguments)
        } catch (error: SerializationException) {
            return call.failedResult(
                arguments = arguments,
                message = "failed to parse function arguments: ${error.message}",
            )
        }
        return handler(call, input, arguments)
    }
}

private fun ResponseItem.FunctionCall.failedResult(
    arguments: JsonElement,
    message: String,
): Pair<FunctionCallOutputPayload, StableCleanEvent.CompletedTool> =
    failedOutput(message) to StableTextToolEvent(
        name = name,
        namespace = namespace,
        arguments = arguments,
        result = message,
        success = false,
    )

private fun failedOutput(message: String): FunctionCallOutputPayload =
    FunctionCallOutputPayload(
        body = FunctionCallOutputBody.Text(message),
        success = false,
    )
