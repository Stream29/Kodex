@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpCodexImportSource
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManagerEffect
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** Default application-wide [McpManager] implementation. */
public expect class McpManagerImpl : McpManager {
    override val servers: StateFlow<List<McpManagedServerState>>
    override val effects: Flow<McpManagerEffect>

    override suspend fun add(draft: McpServerDraft)
    override suspend fun edit(existingServerName: String, draft: McpServerDraft)
    override suspend fun delete(serverName: String)
    override suspend fun setEnabled(serverName: String, enabled: Boolean)
    override suspend fun login(serverName: String)
    override suspend fun cancelLogin(serverName: String)
    override suspend fun logout(serverName: String)
    override suspend fun reconnect(serverName: String)
    override suspend fun previewCodexImport(filter: String): McpImportPreview
    override suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    )
    override fun close()

    /**
     * Runs browser authorization using [onPrepared] to deliver the prepared attempt.
     * @throws IllegalArgumentException If another attempt is active, the server cannot use OAuth,
     * or a redirect/identity check fails.
     * @throws IllegalStateException If the manager is closed, or the server is removed or stops
     * using HTTP during authorization.
     * @throws Throwable If attempt creation, persistence, the callback, exchange or cleanup fails.
     */
    public suspend fun loginWithPreparedCallback(
        serverName: String,
        expectedRedirectUri: String? = null,
        onPrepared: suspend (McpOAuthLoginAttempt) -> Unit,
    )

    /**
     * Coordinates a full-value settings commit with manager commands.
     * [commit] must compare/persist at the actual store's write boundary; this
     * command lock is not a replacement for file CAS or token-refresh locking.
     * No Replace intent, invalidate or reconnect is inferred from equal values.
     * @throws IllegalStateException If the manager is closed.
     * @throws Throwable If [commit] fails.
     */
    public suspend fun commitConfigurationChange(
        expect: Map<String, McpServerConfiguration>,
        update: Map<String, McpServerConfiguration>,
        commit: suspend () -> Boolean,
    ): Boolean
}

/** Creates an independently owned manager under this scope. */
public expect fun CoroutineScope.McpManagerImpl(
    store: McpConfigurationStore,
    service: McpService,
    codexImportSource: McpCodexImportSource,
    loginAttemptFactory: McpOAuthLoginAttemptFactory,
): McpManagerImpl

/**
 * Application-wide [McpService] backed by the MCP client.
 *
 * Complete settings snapshots retain unchanged client owners and replace only configurations
 * whose connection identity changed. Connecting and failed clients remain published.
 */
public expect class McpServiceImpl : McpService, CoroutineScope {
    override val coroutineContext: CoroutineContext
    override val clients: StateFlow<Map<String, McpClient>>
    override val authentication: StateFlow<Map<String, McpAuthenticationState>>
    override suspend fun invalidate(serverName: String)
    override suspend fun refresh()
    override fun close()
}

/**
 * Creates an independently cancellable MCP service under this scope.
 *
 * @throws IllegalArgumentException if the initial settings contain ambiguous server model routes,
 * before creating any resource or lifecycle child.
 */
public expect fun CoroutineScope.McpServiceImpl(
    settings: StateFlow<McpSettings>,
    configurationStore: McpConfigurationStore? = null,
    tokenRefresher: McpOAuthTokenRefresher? = null,
): McpServiceImpl

/** Shared OAuth browser-login and token-refresh implementation for Streamable HTTP servers. */
public expect class DefaultMcpOAuthClient : McpOAuthLoginAttemptFactory, McpOAuthTokenRefresher {
    override suspend fun create(
        configuration: McpServerConfiguration.StreamableHttp,
    ): McpOAuthLoginAttempt

    /**
     * Discovers/registers and prepares PKCE without opening a callback listener.
     *
     * @throws IllegalArgumentException if the configuration is not OAuth-uninitialized or OAuth
     * identity/capability validation fails.
     * @throws IllegalStateException if required metadata fields are missing, neither a client id
     * nor a registration endpoint is available, or dynamic registration is rejected.
     */
    public suspend fun prepare(
        configuration: McpServerConfiguration.StreamableHttp,
    ): PreparedMcpOAuthLogin

    override suspend fun refresh(
        configuration: McpOAuthConfiguration.Initialized,
    ): McpOAuthConfiguration.Initialized
}

/** Creates one OAuth client whose HTTP lifetime follows this scope. */
public expect fun CoroutineScope.DefaultMcpOAuthClient(): DefaultMcpOAuthClient

/** Prepared authorization data without exposing the exchange callback's captured state. */
public expect class PreparedMcpOAuthLogin(
    preparedConfiguration: McpOAuthConfiguration.Uninitialized,
    state: String,
    authorizationUrl: String,
    exchange: suspend (String) -> McpOAuthConfiguration.Initialized,
) {
    public val preparedConfiguration: McpOAuthConfiguration.Uninitialized
    public val state: String
    public val authorizationUrl: String

    /**
     * Delegates [code] to the callback and returns its result.
     * @throws Throwable If the supplied exchange callback fails or is cancelled.
     */
    public suspend fun exchangeCode(code: String): McpOAuthConfiguration.Initialized
}

/**
 * Validates complete proposals at the persistence boundary without normalizing values or silently
 * changing the CAS update. Runtime credentials cannot move to a different server identity.
 *
 * @throws IllegalArgumentException if names are blank or ambiguous, a changed transport's required
 * configuration is blank, or initialized credentials are moved to a different server identity.
 */
public expect fun validateMcpConfigurationUpdate(
    current: Map<String, McpServerConfiguration>,
    update: Map<String, McpServerConfiguration>,
)
