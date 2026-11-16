@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthSummary
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Default application-wide [McpManager] implementation. */
public actual class McpManagerImpl internal constructor(
    scope: CoroutineScope,
    private val store: McpConfigurationStore,
    private val service: McpService,
    private val loginPreparer: McpOAuthLoginPreparer,
) : McpManager {
    private val scope = scope.supervisorChildScope()
    private val commandMutex = Mutex()
    private val authenticationOverrides =
        MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())
    private val mutableServers = MutableStateFlow<List<McpManagedServerState>>(emptyList())
    private val activeLoginJobs = mutableMapOf<String, Job>()
    private var closed = false

    actual override val servers: StateFlow<List<McpManagedServerState>> = mutableServers.asStateFlow()

    init {
        this.scope.launch {
            combine(
                store.configurations,
                service.clients,
                service.authentication,
                authenticationOverrides,
            ) { configurations, clients, runtimeAuthentication, overrides ->
                ManagerSnapshot(
                    configurations = configurations,
                    clients = clients,
                    authentication = runtimeAuthentication + overrides,
                )
            }.collectLatest { snapshot ->
                publish(snapshot)
                coroutineScope {
                    snapshot.clients.values.forEach { client ->
                        launch {
                            client.state.collect {
                                publish(
                                    snapshot.copy(
                                        configurations = store.configurations.value,
                                        clients = service.clients.value,
                                        authentication = service.authentication.value +
                                            authenticationOverrides.value,
                                    ),
                                )
                            }
                        }
                    }
                    awaitCancellation()
                }
            }
        }
    }

    /** Backend callback instead of broadcasting an authorization URL as a UI effect. */
    public actual suspend fun loginWithPreparedCallback(
        serverName: String,
        expectedRedirectUri: String?,
        awaitAuthorizationCode: suspend (PreparedMcpOAuthLogin) -> String,
    ) {
        val operation = command {
            require(serverName !in activeLoginJobs) {
                "MCP server '$serverName' is already authorizing."
            }
            val configuration = store.configurations.value[serverName]
                as? McpServerConfiguration.StreamableHttp
                ?: throw IllegalArgumentException(
                    "MCP server '$serverName' does not support browser OAuth.",
                )
            val oauth = configuration.oauth
                ?: throw IllegalArgumentException(
                    "MCP server '$serverName' has no OAuth configuration.",
                )
            require(expectedRedirectUri == null || oauth.client.redirectUri == expectedRedirectUri) {
                "The callback address does not match the MCP configuration."
            }
            val uninitialized = oauth.toUninitialized()
            val loginConfiguration = configuration.copy(oauth = uninitialized)
            authenticationOverrides.value +=
                serverName to McpAuthenticationState.Authorizing
            val preparedLogin = try {
                loginPreparer.prepare(loginConfiguration)
            } catch (cancellation: CancellationException) {
                authenticationOverrides.value -= serverName
                throw cancellation
            } catch (failure: Throwable) {
                authenticationOverrides.value +=
                    serverName to McpAuthenticationState.Failed("Authorization could not start.")
                throw failure
            }
            val prepared = preparedLogin.preparedConfiguration
            try {
                require(expectedRedirectUri == null || prepared.client.redirectUri == expectedRedirectUri) {
                    "The callback address does not match the MCP configuration."
                }
                if (prepared != uninitialized) {
                    store.update { current ->
                        val latest = current[serverName]
                            as? McpServerConfiguration.StreamableHttp
                            ?: throw IllegalStateException(
                                "MCP server '$serverName' changed during authorization.",
                            )
                        require(
                            latest.url == configuration.url &&
                                latest.oauth?.loginIdentity() == uninitialized.loginIdentity(),
                        ) {
                            "MCP server '$serverName' changed during authorization."
                        }
                        current + (serverName to latest.copy(oauth = prepared))
                    }
                }
            } catch (cancellation: CancellationException) {
                authenticationOverrides.value -= serverName
                throw cancellation
            } catch (failure: Throwable) {
                authenticationOverrides.value +=
                    serverName to McpAuthenticationState.Failed("Authorization could not start.")
                throw failure
            }
            val job = currentCoroutineContext().job
            activeLoginJobs[serverName] = job
            LoginOperation(configuration.url, prepared, preparedLogin, job)
        }
        try {
            val code = awaitAuthorizationCode(operation.preparedLogin)
            currentCoroutineContext().ensureActive()
            val initialized = operation.preparedLogin.exchangeCode(code)
            currentCoroutineContext().ensureActive()
            command {
                store.update { current ->
                    val latest = current[serverName]
                        as? McpServerConfiguration.StreamableHttp
                        ?: throw IllegalStateException(
                            "MCP server '$serverName' changed during authorization.",
                        )
                    require(
                        latest.url == operation.serverUrl &&
                            latest.oauth?.loginIdentity() == operation.uninitialized.loginIdentity(),
                    ) {
                        "MCP server '$serverName' changed during authorization."
                    }
                    current + (serverName to latest.copy(oauth = initialized))
                }
                authenticationOverrides.value -= serverName
            }
        } catch (cancellation: CancellationException) {
            authenticationOverrides.value -= serverName
            throw cancellation
        } catch (failure: Throwable) {
            authenticationOverrides.value +=
                serverName to McpAuthenticationState.Failed("Authorization failed.")
            throw failure
        } finally {
            withContext(NonCancellable) {
                commandMutex.withLock {
                    if (activeLoginJobs[serverName] === operation.job) {
                        activeLoginJobs.remove(serverName)
                    }
                }
            }
        }
    }

    /*
     * Keep logout independent from browser-attempt cancellation: the caller
     * explicitly cancels first when dismissing a pending authorization.
     */
    actual override suspend fun logout(serverName: String) {
        command {
            require(serverName !in activeLoginJobs) {
                "MCP server '$serverName' is currently authorizing."
            }
            store.update { current ->
                val latest = current[serverName]
                    as? McpServerConfiguration.StreamableHttp
                    ?: throw IllegalArgumentException(
                        "MCP server '$serverName' does not support OAuth.",
                    )
                val oauth = latest.oauth
                    ?: throw IllegalArgumentException(
                        "MCP server '$serverName' has no OAuth configuration.",
                    )
                current + (serverName to latest.copy(oauth = oauth.toUninitialized()))
            }
            authenticationOverrides.value -= serverName
        }
    }

    actual override suspend fun reconnect(serverName: String) {
        val client = service.clients.value[serverName]
            ?: throw IllegalArgumentException("MCP server '$serverName' is not connected.")
        client.reconnect()
    }

    /**
     * Coordinates a full-value settings commit with manager commands.
     * [commit] must compare/persist at the actual store's write boundary; this
     * command lock is not a replacement for file CAS or token-refresh locking.
     * No Replace intent, invalidate or reconnect is inferred from equal values.
     */
    public actual suspend fun commitConfigurationChange(
        expect: Map<String, McpServerConfiguration>,
        update: Map<String, McpServerConfiguration>,
        commit: suspend () -> Boolean,
    ): Boolean = command {
        if (!commit()) return@command false
        val changedNames = (expect.keys + update.keys).filter { expect[it] != update[it] }
        changedNames.filter { it !in update }.forEach { activeLoginJobs[it]?.cancel() }
        authenticationOverrides.value -= changedNames.toSet()
        true
    }

    actual override fun close() {
        if (closed) return
        closed = true
        activeLoginJobs.values.forEach { job -> job.cancel() }
        activeLoginJobs.clear()
        scope.cancel()
    }

    private suspend fun <T> command(block: suspend () -> T): T {
        check(!closed) { "The MCP manager is closed." }
        return commandMutex.withLock { block() }
    }

    private fun publish(snapshot: ManagerSnapshot) {
        mutableServers.value = snapshot.configurations
            .entries
            .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
            .map { (name, configuration) ->
                val client = snapshot.clients[name]
                McpManagedServerState(
                    serverName = name,
                    transport = configuration.transportKind(),
                    enabled = configuration.enabled,
                    authentication = snapshot.authentication[name]
                        ?: configuration.persistedAuthenticationState(),
                    connection = client?.state?.value,
                    toolCount = client?.listTools()?.size ?: 0,
                    headerNames = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.headers?.keys?.sorted().orEmpty(),
                    environmentNames = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.environment?.keys?.sorted().orEmpty(),
                    oauth = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.oauth?.toSummary(),
                    streamableHttpUrl = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.url,
                    stdioCommand = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.command,
                    stdioArguments = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.args.orEmpty(),
                    stdioWorkingDirectory = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.workingDirectory,
                )
            }
    }
}

/** Creates an independently owned manager under this scope. */
public actual fun CoroutineScope.McpManagerImpl(
    store: McpConfigurationStore,
    service: McpService,
    loginPreparer: McpOAuthLoginPreparer,
): McpManagerImpl =
    McpManagerImpl(
        scope = this,
        store = store,
        service = service,
        loginPreparer = loginPreparer,
    )

private data class ManagerSnapshot(
    val configurations: Map<String, McpServerConfiguration>,
    val clients: Map<String, McpClient>,
    val authentication: Map<String, McpAuthenticationState>,
)

private data class LoginOperation(
    val serverUrl: String,
    val uninitialized: McpOAuthConfiguration.Uninitialized,
    val preparedLogin: PreparedMcpOAuthLogin,
    val job: Job,
)

private fun McpOAuthConfiguration.toUninitialized(): McpOAuthConfiguration.Uninitialized =
    McpOAuthConfiguration.Uninitialized(
        client = client,
        resource = resource,
        scopes = scopes,
    )

private data class OAuthLoginIdentity(
    val clientId: String?,
    val clientSecret: McpSecret?,
    val redirectUri: String,
    val authorizationEndpoint: String?,
    val tokenEndpoint: String?,
    val resource: String?,
    val scopes: List<String>,
)

private fun McpOAuthConfiguration.loginIdentity(): OAuthLoginIdentity =
    OAuthLoginIdentity(
        clientId = client.clientId,
        clientSecret = client.clientSecret,
        redirectUri = client.redirectUri,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        resource = resource,
        scopes = scopes,
    )

private fun McpServerConfiguration.transportKind(): McpTransportKind =
    when (this) {
        is McpServerConfiguration.StreamableHttp -> McpTransportKind.StreamableHttp
        is McpServerConfiguration.Stdio -> McpTransportKind.Stdio
    }

private fun McpOAuthConfiguration.toSummary(): McpOAuthSummary =
    McpOAuthSummary(
        clientId = client.clientId,
        hasClientSecret = client.clientSecret != null,
        redirectUri = client.redirectUri,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        resource = resource,
        scopes = scopes,
    )
