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
 * @property updatedInput Decoded for wire compatibility but ignored because
 * Codex Lite does not allow PreToolUse to rewrite tool calls.
 * @property additionalContext Decoded for wire compatibility but intentionally
 * ignored because Codex Lite Tool Hooks cannot inject persistent context.
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
        return stderr.toPreToolUseBlock()
    }
    if (exitCode != 0 || stdout.isBlank()) return PreToolUseResult.Continue

    val wire = decodeHookOutputOrNull<PreToolUseCommandOutputWire>(stdout)
        ?: return PreToolUseResult.Continue
    if (!wire.continueProcessing || wire.stopReason != null || wire.suppressOutput) {
        return PreToolUseResult.Continue
    }
    val specific = wire.hookSpecificOutput
    val usesSpecificDecision = specific?.let { output ->
        output.permissionDecision != null ||
            output.permissionDecisionReason != null
    } == true

    if (usesSpecificDecision) {
        return when (specific.permissionDecision) {
            PreToolUsePermissionDecisionWire.Deny ->
                specific.permissionDecisionReason.toPreToolUseBlock()

            PreToolUsePermissionDecisionWire.Allow,
            PreToolUsePermissionDecisionWire.Ask,
            null,
                -> PreToolUseResult.Continue
        }
    }
    return when (wire.decision) {
        PreToolUseDecisionWire.Block ->
            wire.reason.toPreToolUseBlock()

        PreToolUseDecisionWire.Approve,
        null,
            -> PreToolUseResult.Continue
    }
}

private fun String?.toPreToolUseBlock(): PreToolUseResult.Block =
    this?.trimmedNonEmpty()?.let(PreToolUseResult::Block) ?: PreToolUseResult.Block()
