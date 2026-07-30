package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.agentstorage.cleanmodels.CleanOpenAiEvent
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.Serializable

/**
 * Durable local tool call that has not produced a durable result yet.
 *
 * Every concrete event owns its typed input and provider-facing projection.
 * Runtime-only input deltas and provider call status are not represented here.
 */
@Serializable
public sealed interface PendingToolEvent : CleanOpenAiEvent {
    public val callId: String
    public val itemId: ResponseItemId?
}
