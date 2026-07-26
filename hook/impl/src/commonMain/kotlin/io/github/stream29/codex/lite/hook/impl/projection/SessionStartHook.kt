package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.session.SessionStartResult
import io.github.stream29.codex.lite.hook.impl.HookRawResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SessionStartCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "SessionStart",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    val source: String,
)

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property hookSpecificOutput `null` means no SessionStart context was produced.
 */
@Serializable
internal data class SessionStartCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val hookSpecificOutput: SessionStartHookSpecificOutputWire? = null,
)

@Serializable
internal data class SessionStartHookSpecificOutputWire(
    val hookEventName: HookEventNameWire,
    /** `null` means no model-visible context was produced. */
    val additionalContext: String? = null,
)

internal fun HookRawResult.toSessionStartResult(): SessionStartResult {
    if (exitCode != 0) return SessionStartResult.Continue()
    val output = stdout.trim()
    if (output.isEmpty()) return SessionStartResult.Continue()
    val wire = decodeHookOutputOrNull<SessionStartCommandOutputWire>(output)
        ?: return if (output.looksLikeJson()) {
            SessionStartResult.Continue()
        } else {
            SessionStartResult.Continue(listOf(output))
        }
    val contexts = wire.hookSpecificOutput?.additionalContext?.let(::listOf).orEmpty()
    return if (wire.continueProcessing) {
        SessionStartResult.Continue(contexts)
    } else {
        SessionStartResult.Stop(wire.stopReason, contexts)
    }
}
