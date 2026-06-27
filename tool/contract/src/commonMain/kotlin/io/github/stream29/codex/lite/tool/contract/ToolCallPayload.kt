package io.github.stream29.codex.lite.tool.contract

import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal union of OpenAI tool-call items accepted by local tool handlers.
 */
@Serializable
public sealed interface ToolCallPayload {
    public val input: String
    public val logPayload: String get() = input

    /**
     * Function-tool call whose `arguments` field contains JSON text.
     */
    @Serializable
    @SerialName("function_call")
    public data class FunctionCall(
        public val call: ResponseItem.FunctionCall,
    ) : ToolCallPayload {
        override val input: String get() = call.arguments
    }

    /**
     * Freeform custom-tool call whose `input` field contains raw text.
     */
    @Serializable
    @SerialName("custom_tool_call")
    public data class CustomToolCall(
        public val call: ResponseItem.CustomToolCall,
    ) : ToolCallPayload {
        override val input: String get() = call.input
    }
}
