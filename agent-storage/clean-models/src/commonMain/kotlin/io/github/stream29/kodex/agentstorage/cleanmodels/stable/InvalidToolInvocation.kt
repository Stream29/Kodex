package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Protocol-specific input of a tool call rejected during parsing.
 *
 * The input remains raw only where parsing made a stronger representation
 * impossible. Provider call status and raw output items are intentionally
 * omitted.
 */
@Serializable
public sealed interface InvalidToolInvocation {
    /** Function call whose argument string could not be decoded by its tool. */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val namespace: String? = null,
        public val arguments: String,
    ) : InvalidToolInvocation

    /** Custom call whose freeform input could not be decoded by its tool. */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        public val name: String,
        public val namespace: String? = null,
        public val input: String,
    ) : InvalidToolInvocation

    /** Client tool-search call whose JSON arguments did not match its schema. */
    @Serializable
    @SerialName("tool_search")
    public data class ToolSearch(
        public val arguments: JsonElement,
    ) : InvalidToolInvocation
}

internal fun InvalidToolInvocation.toResponseHistoryItems(
    callId: String,
    itemId: ResponseItemId?,
    message: String,
): List<ResponseItem.HistoryItem> =
    when (this) {
        is InvalidToolInvocation.Function ->
            listOf(
                stableFunctionCall(
                    callId = callId,
                    itemId = itemId,
                    name = name,
                    namespace = namespace,
                    arguments = arguments,
                ),
                stableTextOutput(
                    callId = callId,
                    text = message,
                    success = false,
                ),
            )

        is InvalidToolInvocation.Custom ->
            listOf(
                ResponseItem.CustomToolCall(
                    id = itemId,
                    callId = callId,
                    name = name,
                    namespace = namespace,
                    input = input,
                ),
                ResponseItem.CustomToolCallOutput(
                    callId = callId,
                    output = FunctionCallOutputPayload.fromText(message).copy(success = false),
                ),
            )

        is InvalidToolInvocation.ToolSearch ->
            listOf(
                ResponseItem.ClientToolSearchCall(
                    id = itemId,
                    callId = callId,
                    arguments = arguments,
                ),
                ResponseItem.ClientToolSearchOutput(
                    callId = callId,
                    status = "completed",
                    tools = emptyList(),
                ),
            )
    }
