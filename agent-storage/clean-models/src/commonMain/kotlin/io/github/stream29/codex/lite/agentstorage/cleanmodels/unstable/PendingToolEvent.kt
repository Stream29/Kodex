package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Durable tool call that has not produced a durable result yet.
 *
 * [callId] only correlates the pending call with its future result. Once the
 * result is persisted, projection removes this value and appends a completed
 * event to stable history.
 */
@Serializable
@SerialName("pending_tool_call")
public data class PendingToolEvent(
    @SerialName("call_id")
    public val callId: String,
    public val invocation: PendingToolInvocation,
)
