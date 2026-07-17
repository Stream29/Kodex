package io.github.stream29.codex.lite.tool.builder

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    handler: suspend (Input) -> JsonToolHandlerResult<Output>,
): Tool =
    JsonTool(spec, inputDeserializer, outputSerializer, json, handler)

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
    handler: suspend (Input) -> JsonToolHandlerResult<String>,
): Tool =
    TextTool(spec, inputDeserializer, json, handler)

private class JsonTool<Input, Output>(
    override val spec: ToolSpec,
    private val inputDeserializer: DeserializationStrategy<Input>,
    private val outputSerializer: SerializationStrategy<Output>,
    private val json: Json,
    private val handler: suspend (Input) -> JsonToolHandlerResult<Output>,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput =
        when (call) {
            is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = handleFunctionCall(call.arguments),
            )

            is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = failedOutput("JSON tool received custom tool payload"),
            )
        }

    private suspend fun handleFunctionCall(argumentsJson: String): FunctionCallOutputPayload {
        val input = try {
            json.decodeFromString(inputDeserializer, argumentsJson)
        } catch (error: SerializationException) {
            return failedOutput("failed to parse function arguments: ${error.message}")
        }

        return when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure -> failedOutput(result.message)
            is JsonToolHandlerResult.Success -> {
                val value = try {
                    json.encodeToString(outputSerializer, result.value)
                } catch (error: SerializationException) {
                    return failedOutput("failed to serialize tool output: ${error.message}")
                }
                FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text(value),
                    success = result.success,
                )
            }
        }
    }

    private fun failedOutput(message: String): FunctionCallOutputPayload =
        FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text(message),
            success = false,
        )
}

private class TextTool<Input>(
    override val spec: ToolSpec,
    private val inputDeserializer: DeserializationStrategy<Input>,
    private val json: Json,
    private val handler: suspend (Input) -> JsonToolHandlerResult<String>,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput =
        when (call) {
            is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = handleFunctionCall(call.arguments),
            )

            is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = failedOutput("JSON tool received custom tool payload"),
            )
        }

    private suspend fun handleFunctionCall(argumentsJson: String): FunctionCallOutputPayload {
        val input = try {
            json.decodeFromString(inputDeserializer, argumentsJson)
        } catch (error: SerializationException) {
            return failedOutput("failed to parse function arguments: ${error.message}")
        }

        return when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure -> failedOutput(result.message)
            is JsonToolHandlerResult.Success -> FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(result.value),
                success = result.success,
            )
        }
    }

    private fun failedOutput(message: String): FunctionCallOutputPayload =
        FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text(message),
            success = false,
        )
}
