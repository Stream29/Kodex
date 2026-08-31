package io.github.stream29.kodex.tool.builder

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

public val ToolBuilderJson: Json = Json

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
    completedEvent: ((
        PendingFunctionToolEvent,
        Input,
        JsonToolHandlerResult<Output>,
    ) -> StableCleanEvent.CompletedTool)? = null,
    handler: suspend (Input) -> JsonToolHandlerResult<Output>,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { pending, input, arguments ->
        when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure ->
                completedEvent?.invoke(pending, input, result)
                    ?: StableTextToolEvent(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = arguments,
                        result = result.message,
                        success = false,
                    )

            is JsonToolHandlerResult.Success -> try {
                val resultJson = json.encodeToJsonElement(outputSerializer, result.value)
                completedEvent?.invoke(pending, input, result)
                    ?: StableJsonToolEvent(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = arguments,
                        result = resultJson,
                        success = result.success,
                    )
            } catch (error: SerializationException) {
                val message = "failed to serialize tool output: ${error.message}"
                StableTextToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    name = pending.name,
                    namespace = pending.namespace,
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
 * The input is still decoded from the pending function event's JSON arguments.
 * The
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
    completedEvent: ((
        PendingFunctionToolEvent,
        Input,
        JsonToolHandlerResult<String>,
    ) -> StableCleanEvent.CompletedTool)? = null,
    handler: suspend (Input) -> JsonToolHandlerResult<String>,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { pending, input, arguments ->
        when (val result = handler(input)) {
            is JsonToolHandlerResult.Failure ->
                completedEvent?.invoke(pending, input, result)
                    ?: StableTextToolEvent(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = arguments,
                        result = result.message,
                        success = false,
                    )

            is JsonToolHandlerResult.Success ->
                completedEvent?.invoke(pending, input, result)
                    ?: StableTextToolEvent(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = arguments,
                        result = result.value,
                        success = result.success,
                    )
        }
    }

/**
 * Builds a normal JSON-input function tool that returns a protocol-native
 * [StableCleanEvent.CompletedTool].
 *
 * Use this when a successful result contains rich Responses content such as
 * images instead of plain or JSON-encoded text. The returned event is the
 * single source of truth for the projected output. The typed pending event is
 * supplied to the handler for host-owned artifact naming and other call-scoped
 * work.
 *
 * This does not support custom-tool payloads such as `apply_patch`.
 */
public fun <Input> functionOutputTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    json: Json = ToolBuilderJson,
    handler: suspend (
        pending: PendingFunctionToolEvent,
        input: Input,
    ) -> StableCleanEvent.CompletedTool,
): Tool =
    FunctionOutputTool(spec, inputDeserializer, json) { pending, input, _ ->
        handler(pending, input)
    }

private class FunctionOutputTool<Input>(
    override val spec: ToolSpec,
    private val inputDeserializer: DeserializationStrategy<Input>,
    private val json: Json,
    private val handler: suspend (
        pending: PendingFunctionToolEvent,
        input: Input,
        arguments: JsonElement,
    ) -> StableCleanEvent.CompletedTool,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
        when (pending) {
            is PendingFunctionToolEvent -> handleFunctionCall(pending)

            is PendingCustomToolEvent -> {
                StableCustomToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    name = pending.name,
                    namespace = pending.namespace,
                    input = pending.input,
                    result = failedOutput(),
                    success = false,
                )
            }

            else -> error("JSON tools require a pending function or custom tool event.")
        }

    private suspend fun handleFunctionCall(
        pending: PendingFunctionToolEvent,
    ): StableCleanEvent.CompletedTool {
        val input = try {
            json.decodeFromJsonElement(inputDeserializer, pending.arguments)
        } catch (error: SerializationException) {
            return pending.failedResult(
                message = "failed to parse function arguments: ${error.message}",
            )
        }
        return handler(pending, input, pending.arguments)
    }
}

private fun PendingFunctionToolEvent.failedResult(
    message: String,
): StableCleanEvent.CompletedTool =
    StableTextToolEvent(
        callId = callId,
        itemId = itemId,
        name = name,
        namespace = namespace,
        arguments = arguments,
        result = message,
        success = false,
    )

private fun failedOutput(): FunctionCallOutputPayload =
    FunctionCallOutputPayload(
        body = FunctionCallOutputBody.Text("JSON tool received custom tool payload"),
        success = false,
    )
