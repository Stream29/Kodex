package io.github.stream29.kodex.tool.builder

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * Result algebra shared by JSON tool adapters.
 *
 * The implementation module owns event construction and JSON decoding; this
 * type is the stable boundary for handlers that only need to report success
 * or a model-facing failure.
 */
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

public expect val ToolBuilderJson: Json

public expect fun <Input, Output> jsonTool(
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
): Tool

/**
 * Builds a normal JSON-input function tool whose successful result is sent as
 * raw text instead of JSON-encoded text.
 *
 * The input is still decoded from the pending function event's JSON arguments.
 * The difference is only the successful function-call output: for example,
 * [jsonTool] with `String.serializer()` sends `"hello"`, while this function
 * sends `hello` unchanged. Use this for Responses API tools whose documented
 * output is model-facing text rather than a JSON value. Failures retain the
 * usual `success = false` text output.
 *
 * This does not support custom-tool payloads such as `apply_patch`.
 */
public expect fun <Input> textTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    json: Json = ToolBuilderJson,
    completedEvent: ((
        PendingFunctionToolEvent,
        Input,
        JsonToolHandlerResult<String>,
    ) -> StableCleanEvent.CompletedTool)? = null,
    handler: suspend (Input) -> JsonToolHandlerResult<String>,
): Tool

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
public expect fun <Input> functionOutputTool(
    spec: ToolSpec,
    inputDeserializer: DeserializationStrategy<Input>,
    json: Json = ToolBuilderJson,
    handler: suspend (
        pending: PendingFunctionToolEvent,
        input: Input,
    ) -> StableCleanEvent.CompletedTool,
): Tool
