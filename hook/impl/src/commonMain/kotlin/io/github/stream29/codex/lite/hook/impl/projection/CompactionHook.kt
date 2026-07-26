package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookResult
import io.github.stream29.codex.lite.hook.impl.HookRawResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CompactCommandInputWire(
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    /** `null` means the session has no host-visible transcript file. */
    @SerialName("transcript_path") val transcriptPath: String?,
    val cwd: String,
    @SerialName("hook_event_name") val hookEventName: String,
    val model: String,
    val trigger: String,
)

internal fun CompactionHookRequest.toPreCompactCommandInputWire(): CompactCommandInputWire =
    toCompactCommandInputWire("PreCompact")

internal fun CompactionHookRequest.toPostCompactCommandInputWire(): CompactCommandInputWire =
    toCompactCommandInputWire("PostCompact")

private fun CompactionHookRequest.toCompactCommandInputWire(
    hookEventName: String,
): CompactCommandInputWire {
    val session = context.session
    return CompactCommandInputWire(
        sessionId = session.sessionId,
        turnId = context.turnId,
        transcriptPath = null,
        cwd = session.cwd.toString(),
        hookEventName = hookEventName,
        model = session.model,
        trigger = trigger.wireName,
    )
}

/**
 * @property stopReason `null` means the handler supplied no stop explanation.
 * @property systemMessage `null` means the handler supplied no audit warning.
 */
@Serializable
internal data class CompactCommandOutputWire(
    @SerialName("continue") val continueProcessing: Boolean = true,
    val stopReason: String? = null,
    val suppressOutput: Boolean = false,
    val systemMessage: String? = null,
)

internal fun HookRawResult.toCompactionResult(): CompactionHookResult {
    if (exitCode != 0 || stdout.isBlank()) return CompactionHookResult.Continue
    val wire = decodeHookOutputOrNull<CompactCommandOutputWire>(stdout)
        ?: return CompactionHookResult.Continue
    return if (wire.continueProcessing) {
        CompactionHookResult.Continue
    } else {
        CompactionHookResult.Stop(wire.stopReason)
    }
}
