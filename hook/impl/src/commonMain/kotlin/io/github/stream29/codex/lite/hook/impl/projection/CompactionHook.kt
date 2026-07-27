package io.github.stream29.codex.lite.hook.impl.projection

import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
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
