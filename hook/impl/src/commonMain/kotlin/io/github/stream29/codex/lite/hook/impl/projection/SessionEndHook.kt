package io.github.stream29.codex.lite.hook.impl.projection

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SessionEndCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "SessionEnd",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    val reason: String,
)
