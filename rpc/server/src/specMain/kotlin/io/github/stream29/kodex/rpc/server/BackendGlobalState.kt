@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.cli.settings.UnifiedSettingsStore

/**
 * Backend-owned settings/models/MCP state; its resource constructor is implementation-only.
 */
public expect class BackendGlobalState {
    public val unified: UnifiedSettingsStore

    /**
     * Explicitly reloads settings and applies MCP configuration coordination.
     *
     * @throws IllegalStateException if settings changed while the reload candidate was read.
     * @throws Throwable if reading, validation, persistence or MCP coordination fails.
     */
    public suspend fun reloadSettings(): Unit
}
