package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.HookRawResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UserPromptSubmitCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "UserPromptSubmit",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    val prompt: String,
)

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property decision `null` means this handler allows model execution to
 * continue from the persisted user prompt.
 * @property reason `null` means no block explanation was supplied.
 * @property hookSpecificOutput `null` means no additional context was supplied.
 */
@Serializable
internal data class UserPromptSubmitCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val decision: BlockDecisionWire? = null,
    val reason: String? = null,
    val hookSpecificOutput: UserPromptSubmitHookSpecificOutputWire? = null,
)

@Serializable
internal data class UserPromptSubmitHookSpecificOutputWire(
    val hookEventName: HookEventNameWire,
    /** `null` means no model-visible context was produced. */
    val additionalContext: String? = null,
)

internal fun HookRawResult.toUserPromptSubmitResult(): UserPromptSubmitResult {
    if (exitCode == 2) {
        return stderr.trimmedNonEmpty()
            ?.let { reason -> UserPromptSubmitResult.Stop(reason) }
            ?: UserPromptSubmitResult.Continue()
    }
    if (exitCode != 0) return UserPromptSubmitResult.Continue()
    val output = stdout.trim()
    if (output.isEmpty()) return UserPromptSubmitResult.Continue()
    val wire = decodeHookOutputOrNull<UserPromptSubmitCommandOutputWire>(output)
        ?: return if (output.looksLikeJson()) {
            UserPromptSubmitResult.Continue()
        } else {
            UserPromptSubmitResult.Continue(listOf(output))
        }
    val shouldBlock = wire.decision == BlockDecisionWire.Block
    val valid = !shouldBlock || wire.reason?.trimmedNonEmpty() != null
    val contexts = wire.hookSpecificOutput
        ?.additionalContext
        ?.takeIf { valid }
        ?.let(::listOf)
        .orEmpty()
    if (!wire.continueProcessing) {
        return UserPromptSubmitResult.Stop(wire.stopReason, contexts)
    }
    return if (shouldBlock && valid) {
        UserPromptSubmitResult.Stop(wire.reason, contexts)
    } else {
        UserPromptSubmitResult.Continue(contexts)
    }
}
