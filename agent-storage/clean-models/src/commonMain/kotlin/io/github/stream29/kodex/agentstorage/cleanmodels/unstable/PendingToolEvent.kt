package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.Serializable

/**
 * Durable client-executable tool call that has not produced a durable result
 * yet.
 *
 * Every concrete event owns its typed input and provider-facing projection.
 * Runtime-only input deltas and provider call status are not represented here.
 */
@Serializable
public sealed interface PendingToolEvent : UnstableCleanEvent {
    public val callId: String
    public val itemId: ResponseItemId?

    /** Model-visible name used to route a locally executable tool, if any. */
    public val toolName: String?

    /** Model-visible namespace used together with [toolName] for routing. */
    public val toolNamespace: String?
}
