package io.github.stream29.kodex.hook.impl.projection

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PostToolUseCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "PostToolUse",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonElement,
    @SerialName("tool_response") val toolResponse: JsonElement,
    @SerialName("tool_use_id") val toolUseId: String,
)
