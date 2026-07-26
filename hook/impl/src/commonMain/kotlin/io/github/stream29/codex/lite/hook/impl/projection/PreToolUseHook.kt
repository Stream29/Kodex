package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.impl.HookRawResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PreToolUseCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "PreToolUse",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_input") val toolInput: JsonElement,
    @SerialName("tool_use_id") val toolUseId: String,
)

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property decision `null` means the legacy PreToolUse decision is absent.
 * @property reason `null` means no legacy decision reason was supplied.
 * @property hookSpecificOutput `null` means no modern PreToolUse output was supplied.
 */
@Serializable
internal data class PreToolUseCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val decision: PreToolUseDecisionWire? = null,
    val reason: String? = null,
    val hookSpecificOutput: PreToolUseHookSpecificOutputWire? = null,
)

/**
 * @property permissionDecision `null` means this output makes no permission decision.
 * @property permissionDecisionReason `null` means no decision explanation was supplied.
 * @property updatedInput `null` means the original tool input remains in effect.
 * @property additionalContext `null` means no model-visible context was produced.
 */
@Serializable
internal data class PreToolUseHookSpecificOutputWire(
    val hookEventName: HookEventNameWire,
    val permissionDecision: PreToolUsePermissionDecisionWire? = null,
    val permissionDecisionReason: String? = null,
    val updatedInput: JsonElement? = null,
    val additionalContext: String? = null,
)

@Serializable
internal enum class PreToolUseDecisionWire {
    @SerialName("approve") Approve,
    @SerialName("block") Block,
}

@Serializable
internal enum class PreToolUsePermissionDecisionWire {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
    @SerialName("ask") Ask,
}

internal fun HookRawResult.toPreToolUseResult(): PreToolUseResult {
    if (exitCode == 2) {
        return stderr.trimmedNonEmpty()
            ?.let { reason -> PreToolUseResult.Block(reason) }
            ?: PreToolUseResult.Continue()
    }
    if (exitCode != 0 || stdout.isBlank()) return PreToolUseResult.Continue()

    val wire = decodeHookOutputOrNull<PreToolUseCommandOutputWire>(stdout)
        ?: return PreToolUseResult.Continue()
    val specific = wire.hookSpecificOutput
    val usesSpecificDecision = specific?.let { output ->
        output.permissionDecision != null ||
            output.permissionDecisionReason != null ||
            output.updatedInput != null
    } == true
    if (!wire.supportsPreToolUse(usesSpecificDecision)) return PreToolUseResult.Continue()

    val contexts = specific?.additionalContext?.let(::listOf).orEmpty()
    if (usesSpecificDecision) {
        return when (specific.permissionDecision) {
            PreToolUsePermissionDecisionWire.Allow ->
                PreToolUseResult.Continue(requireNotNull(specific.updatedInput), contexts)

            PreToolUsePermissionDecisionWire.Deny ->
                PreToolUseResult.Block(specific.permissionDecisionReason?.trim(), contexts)

            PreToolUsePermissionDecisionWire.Ask,
            null,
                -> PreToolUseResult.Continue(additionalContexts = contexts)
        }
    }
    return when (wire.decision) {
        PreToolUseDecisionWire.Block -> PreToolUseResult.Block(wire.reason?.trim(), contexts)
        PreToolUseDecisionWire.Approve,
        null,
            -> PreToolUseResult.Continue(additionalContexts = contexts)
    }
}

private fun PreToolUseCommandOutputWire.supportsPreToolUse(usesSpecificDecision: Boolean): Boolean {
    if (!continueProcessing || stopReason != null || suppressOutput) return false
    val specific = hookSpecificOutput
    return if (usesSpecificDecision) {
        when {
            specific == null -> false
            specific.updatedInput != null &&
                specific.permissionDecision != PreToolUsePermissionDecisionWire.Allow -> false

            specific.permissionDecision == PreToolUsePermissionDecisionWire.Allow &&
                specific.updatedInput == null -> false

            specific.permissionDecision == PreToolUsePermissionDecisionWire.Ask -> false
            specific.permissionDecision == PreToolUsePermissionDecisionWire.Deny &&
                specific.permissionDecisionReason?.trimmedNonEmpty() == null -> false

            specific.permissionDecision == null && specific.permissionDecisionReason != null -> false
            else -> true
        }
    } else {
        when (decision) {
            PreToolUseDecisionWire.Approve -> false
            PreToolUseDecisionWire.Block -> reason?.trimmedNonEmpty() != null
            null -> reason == null
        }
    }
}
