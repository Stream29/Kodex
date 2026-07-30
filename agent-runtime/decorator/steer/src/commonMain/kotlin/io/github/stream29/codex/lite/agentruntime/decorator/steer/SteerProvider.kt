package io.github.stream29.codex.lite.agentruntime.decorator.steer

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent

/** Provides clean input waiting to steer the active logical turn. */
public fun interface SteerProvider {
    /**
     * Atomically claims the current pending steer.
     *
     * @return the claimed input, or an empty list when no steer is pending.
     */
    public suspend fun take(): List<StableCleanEvent.Steerable>
}
