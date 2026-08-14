package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.impl.HookRawResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class StopCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("hook_event_name") val hookEventName: String = "Stop",
    val model: String,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("stop_hook_active") val stopHookActive: Boolean,
    /** Assistant text, pending input question fallback, or `null` when neither exists. */
    @SerialName("last_assistant_message") val lastAssistantMessage: String?,
)

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 * @property decision `null` means this handler accepts the natural stop.
 * @property reason `null` means no continuation prompt was supplied.
 */
@Serializable
internal data class StopCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
    val decision: BlockDecisionWire? = null,
    val reason: String? = null,
)

internal fun HookRawResult.toStopResult(hookRunId: String): StopResult {
    if (exitCode == 2) {
        return stderr.trimmedNonEmpty()
            ?.let { reason -> StopResult.Continue(listOf(HookPromptFragment(reason, hookRunId))) }
            ?: StopResult.Finish
    }
    if (exitCode != 0 || stdout.isBlank()) return StopResult.Finish

    val wire = decodeHookOutputOrNull<StopCommandOutputWire>(stdout) ?: return StopResult.Finish
    if (!wire.continueProcessing) return StopResult.Stop(wire.stopReason)
    val reason = wire.reason?.trimmedNonEmpty()
    return if (wire.decision == BlockDecisionWire.Block && reason != null) {
        StopResult.Continue(listOf(HookPromptFragment(reason, hookRunId)))
    } else {
        StopResult.Finish
    }
}
