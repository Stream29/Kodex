package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.approval.PermissionRequestResult
import io.github.stream29.codex.lite.hook.impl.HookRawResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PermissionRequestCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "PermissionRequest",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonElement,
)

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property hookSpecificOutput `null` means no PermissionRequest decision was supplied.
 */
@Serializable
internal data class PermissionRequestCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val hookSpecificOutput: PermissionRequestHookSpecificOutputWire? = null,
)

@Serializable
internal data class PermissionRequestHookSpecificOutputWire(
    val hookEventName: HookEventNameWire,
    /** `null` means normal permission handling must continue. */
    val decision: PermissionRequestDecisionWire? = null,
)

/**
 * @property updatedInput `null` means no reserved input rewrite was requested.
 * @property updatedPermissions `null` means no reserved permission rewrite was requested.
 * @property message `null` means the decision supplied no explanation.
 */
@Serializable
internal data class PermissionRequestDecisionWire(
    val behavior: PermissionRequestBehaviorWire,
    val updatedInput: JsonElement? = null,
    val updatedPermissions: JsonElement? = null,
    val message: String? = null,
    val interrupt: Boolean = false,
)

@Serializable
internal enum class PermissionRequestBehaviorWire {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
}

internal fun HookRawResult.toPermissionRequestResult(): PermissionRequestResult {
    if (exitCode == 2) {
        return stderr.trimmedNonEmpty()
            ?.let(PermissionRequestResult::Deny)
            ?: PermissionRequestResult.NoDecision
    }
    if (exitCode != 0 || stdout.isBlank()) return PermissionRequestResult.NoDecision

    val wire = decodeHookOutputOrNull<PermissionRequestCommandOutputWire>(stdout)
        ?: return PermissionRequestResult.NoDecision
    if (!wire.supportsPermissionRequest()) return PermissionRequestResult.NoDecision
    val decision = wire.hookSpecificOutput?.decision ?: return PermissionRequestResult.NoDecision
    return when (decision.behavior) {
        PermissionRequestBehaviorWire.Allow -> PermissionRequestResult.Allow
        PermissionRequestBehaviorWire.Deny -> PermissionRequestResult.Deny(
            decision.message?.trimmedNonEmpty() ?: "PermissionRequest hook denied approval",
        )
    }
}

private fun PermissionRequestCommandOutputWire.supportsPermissionRequest(): Boolean {
    if (!continueProcessing || stopReason != null || suppressOutput) return false
    val decision = hookSpecificOutput?.decision ?: return true
    return decision.updatedInput == null &&
        decision.updatedPermissions == null &&
        !decision.interrupt
}
