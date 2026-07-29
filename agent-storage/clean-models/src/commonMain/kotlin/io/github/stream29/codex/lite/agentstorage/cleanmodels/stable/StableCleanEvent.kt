package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import kotlinx.serialization.Serializable

/**
 * Completed clean event that can be appended to stable history.
 *
 * Stable history follows tool-result persistence order. It does not retain
 * pending call identities or depend on the unstable clean-model package.
 */
@Serializable
public sealed interface StableCleanEvent {
    /**
     * Completed clean event produced by a tool handler.
     *
     * This narrower union prevents tool execution from publishing messages,
     * reasoning, or other non-tool stable events.
     */
    @Serializable
    public sealed interface CompletedTool : StableCleanEvent
}
