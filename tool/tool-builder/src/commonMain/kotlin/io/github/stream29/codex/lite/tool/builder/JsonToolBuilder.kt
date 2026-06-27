package io.github.stream29.codex.lite.tool.builder

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolCallPayload
import io.github.stream29.codex.lite.tool.contract.ToolCallResult
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

private class JsonTool<Input, Output>(
    override val spec: ToolSpec,
    private val inputDeserializer: DeserializationStrategy<Input>,
    private val outputSerializer: SerializationStrategy<Output>,
    private val json: Json,
    private val handler: suspend (Input) -> JsonToolHandlerResult<Output>,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(payload: ToolCallPayload): ToolCallResult {
        val argumentsJson = when (payload) {
            is ToolCallPayload.FunctionCall -> payload.call.arguments
            is ToolCallPayload.CustomToolCall -> return failedOutput("JSON tool received custom tool payload")
        }

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
