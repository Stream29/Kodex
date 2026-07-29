package io.github.stream29.codex.lite.agentruntime.decorator.steer

import io.github.stream29.codex.lite.openai.ContentItem

/** Provides merged user input waiting to steer the active logical turn. */
public fun interface SteerProvider {
    /**
     * Atomically claims the current pending steer.
     *
     * @return the claimed content, or an empty list when no steer is pending.
     */
    public suspend fun take(): List<ContentItem>
}
