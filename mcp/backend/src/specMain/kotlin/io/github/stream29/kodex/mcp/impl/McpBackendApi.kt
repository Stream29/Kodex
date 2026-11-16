@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** Prepares one listener-free browser OAuth login from an uninitialized server declaration. */
public fun interface McpOAuthLoginPreparer {
    /**
     * Discovers/registers OAuth metadata and prepares PKCE without opening a callback listener.
     *
     * @throws Throwable if validation, discovery, or dynamic registration fails.
     */
    public suspend fun prepare(
        configuration: McpServerConfiguration.StreamableHttp,
    ): PreparedMcpOAuthLogin
}

/** Default application-wide [McpManager] implementation. */
public expect class McpManagerImpl : McpManager {
    override val servers: StateFlow<List<McpManagedServerState>>

    override suspend fun logout(serverName: String)
    override suspend fun reconnect(serverName: String)
    override fun close()

    /**
     * Runs browser authorization using [awaitAuthorizationCode] to publish the prepared login and
     * receive the accepted authorization code.
     * @throws IllegalArgumentException If another attempt is active, the server cannot use OAuth,
     * or a redirect/identity check fails.
     * @throws IllegalStateException If the manager is closed, or the server is removed or stops
     * using HTTP during authorization.
     * @throws Throwable If preparation, persistence, callback waiting, or code exchange fails.
     */
    public suspend fun loginWithPreparedCallback(
        serverName: String,
        expectedRedirectUri: String? = null,
        awaitAuthorizationCode: suspend (PreparedMcpOAuthLogin) -> String,
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
    loginPreparer: McpOAuthLoginPreparer,
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
    override fun close()
}

/**
 * Creates an independently cancellable MCP service under this scope.
 */
public expect fun CoroutineScope.McpServiceImpl(
    settings: StateFlow<McpSettings>,
    configurationStore: McpConfigurationStore? = null,
    tokenRefresher: McpOAuthTokenRefresher? = null,
): McpServiceImpl

/** Shared OAuth browser-login preparation and token-refresh implementation for Streamable HTTP servers. */
public expect class DefaultMcpOAuthClient : McpOAuthLoginPreparer, McpOAuthTokenRefresher {
    /**
     * Discovers/registers and prepares PKCE without opening a callback listener.
     *
     * @throws IllegalArgumentException if the configuration is not OAuth-uninitialized or OAuth
     * identity/capability validation fails.
     * @throws IllegalStateException if required metadata fields are missing, neither a client id
     * nor a registration endpoint is available, or dynamic registration is rejected.
     */
    override suspend fun prepare(
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
 * @throws IllegalArgumentException if names are blank, a changed transport's required
 * configuration is blank, or initialized credentials are moved to a different server identity.
 */
public expect fun validateMcpConfigurationUpdate(
    current: Map<String, McpServerConfiguration>,
    update: Map<String, McpServerConfiguration>,
)
