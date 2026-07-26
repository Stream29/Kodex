package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseResult
import io.github.stream29.codex.lite.hook.impl.HookRawResult
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

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property decision `null` means this handler does not block the tool result.
 * @property reason `null` means no block feedback was supplied.
 * @property hookSpecificOutput `null` means no PostToolUse context was supplied.
 */
@Serializable
internal data class PostToolUseCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val decision: BlockDecisionWire? = null,
    val reason: String? = null,
    val hookSpecificOutput: PostToolUseHookSpecificOutputWire? = null,
)

@Serializable
internal data class PostToolUseHookSpecificOutputWire(
    val hookEventName: HookEventNameWire,
    /** `null` means no model-visible context was produced. */
    val additionalContext: String? = null,
    /** `null` means no unsupported MCP output rewrite was requested. */
    @SerialName("updatedMCPToolOutput") val updatedMcpToolOutput: JsonElement? = null,
)

internal fun HookRawResult.toPostToolUseResult(): PostToolUseResult {
    if (exitCode == 2) {
        return stderr.trimmedNonEmpty()
            ?.let(PostToolUseResult::Block)
            ?: PostToolUseResult.Continue()
    }
    if (exitCode != 0 || stdout.isBlank()) return PostToolUseResult.Continue()

    val wire = decodeHookOutputOrNull<PostToolUseCommandOutputWire>(stdout)
        ?: return PostToolUseResult.Continue()
    val shouldBlock = wire.decision == BlockDecisionWire.Block
    val valid = !wire.suppressOutput &&
        wire.hookSpecificOutput?.updatedMcpToolOutput == null &&
        !(shouldBlock && wire.reason?.trimmedNonEmpty() == null) &&
        !(!shouldBlock && wire.continueProcessing && wire.reason != null)
    val contexts = wire.hookSpecificOutput
        ?.additionalContext
        ?.takeIf { valid }
        ?.let(::listOf)
        .orEmpty()
    if (!wire.continueProcessing) {
        return PostToolUseResult.Continue(
            additionalContexts = contexts,
            feedback = wire.reason?.trimmedNonEmpty()
                ?: wire.stopReason
                ?: "PostToolUse hook stopped execution",
        )
    }
    if (!valid) return PostToolUseResult.Continue()
    return if (shouldBlock) {
        PostToolUseResult.Block(requireNotNull(wire.reason), contexts)
    } else {
        PostToolUseResult.Continue(additionalContexts = contexts)
    }
}
