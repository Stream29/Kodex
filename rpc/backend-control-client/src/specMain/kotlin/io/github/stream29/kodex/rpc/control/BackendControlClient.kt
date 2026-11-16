@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalBrowserGrant
import kotlinx.serialization.json.JsonObject

/** Reusable control-plane HTTP client for readiness polling and multi-step companion lifecycles. */
public expect class BackendControlClient() : AutoCloseable {
    public suspend fun request(endpoint: BackendEndpoint, action: String): JsonObject
    public suspend fun registerBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): String
    public suspend fun revokeBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant)
    override fun close()
}

/** Management CLI/launch bootstrap only. No business DTO or TUI recovery dependency. */
public expect suspend fun backendControlRequest(endpoint: BackendEndpoint, action: String): JsonObject
