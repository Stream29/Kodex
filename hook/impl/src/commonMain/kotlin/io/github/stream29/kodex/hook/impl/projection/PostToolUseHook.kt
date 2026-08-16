package io.github.stream29.kodex.hook.impl.projection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PostToolUsePayload(
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonElement,
    @SerialName("tool_response") val toolResponse: JsonElement,
    @SerialName("tool_use_id") val toolUseId: String,
)
