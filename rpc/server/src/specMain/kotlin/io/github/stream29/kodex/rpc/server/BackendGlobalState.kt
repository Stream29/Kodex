@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.cli.settings.BackendSettingsStore
import io.github.stream29.kodex.cli.settings.UnifiedSettingsStore
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import io.github.stream29.kodex.rpc.models.BackendSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Creates the settings/models/MCP resource slice used by the backend's global service.
 *
 * [store], [unified], [client] and [authStore] are borrowed from their enclosing owner and are
 * not closed here. Resources created for this slice are closed after [block] completes. The block
 * receives the live backend-owned state; it must finish using it before returning. This function
 * does not acquire the Session repository or register a partial [BackendGlobalRpc]. These
 * resources are backend-owned; no frontend owns or closes them.
 *
 * Cleanup runs after normal return, failure or cancellation. The primary failure is preserved and
 * cleanup failures are suppressed onto it; a cleanup failure after normal return is propagated.
 *
 * @throws CancellationException if the calling coroutine is cancelled; acquired resources are
 * closed before cancellation escapes.
 * @throws Throwable if resource creation or [block] fails, or cleanup fails after normal return.
 */
public expect suspend fun <R> withBackendGlobalState(
    store: BackendSettingsStore,
    unified: UnifiedSettingsStore,
    agentsHome: Path,
    kodexHome: Path,
    codexHome: Path,
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
    tokenRefresher: McpOAuthTokenRefresher,
    loginAttemptFactory: McpOAuthLoginAttemptFactory,
    block: suspend CoroutineScope.(BackendGlobalState) -> R,
): R

/**
 * Backend-owned settings/models/MCP state; its resource constructor is implementation-only.
 *
 * Suspending operations propagate caller cancellation and their underlying manager/store failures.
 * [compareAndSetSettings] returns false for a stale expected value; validation and persistence
 * failures remain exceptions.
 */
public expect class BackendGlobalState {
    public val store: BackendSettingsStore
    public val mcpConfigurations: McpConfigurationStore
    public val contextSettings: StateFlow<AgentContextSettings>
    public val mcpService: McpService
    public val mcpManager: McpManagerImpl
    public val modelCatalog: OpenAiModelCatalogStore
    public val unified: UnifiedSettingsStore

    public val settings: StateFlow<BackendSettings>
    public val models: StateFlow<List<ModelInfo>>
    public val mcpServers: StateFlow<List<McpManagedServerState>>

    /**
     * Atomically validates and commits the complete settings value, including MCP configuration.
     * Returns false for a value mismatch; validation and persistence failures are not mismatches.
     *
     * @throws Throwable if validation or persistence fails.
     */
    public suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean

    /**
     * Reconnects the exact configured server through the existing manager.
     *
     * @throws Throwable if the named client is absent or reconnect fails.
     */
    public suspend fun reconnectMcpServer(name: String): Unit

    /**
     * Explicitly reloads settings and applies MCP configuration coordination.
     *
     * @throws IllegalStateException if settings changed while the reload candidate was read.
     * @throws Throwable if reading, validation, persistence or MCP coordination fails.
     */
    public suspend fun reloadSettings(): Unit

    /**
     * Reads supported and unsupported MCP declarations from the configured Codex home.
     * A missing configuration yields an empty list; read and parse failures remain failures.
     *
     * @throws Throwable if reading or parsing the Codex configuration fails.
     */
    public suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate>
}
