package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.stdio.openMcpStdioTransport
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
import io.github.stream29.kodex.mcp.streamablehttp.openMcpStreamableHttpTransport
import io.github.stream29.kodex.mcp.streamablehttp.withMcpAuthorization
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Application-wide [McpService] backed by the official Kotlin MCP client.
 *
 * Complete settings snapshots retain unchanged client owners and replace only
 * configurations whose identity changed. Connecting and failed clients remain
 * published so callers can observe and reconnect them.
 */
public class McpServiceImpl internal constructor(
    scope: CoroutineScope,
    private val settings: StateFlow<McpSettings>,
    private val configurationStore: McpConfigurationStore?,
    private val tokenRefresher: McpOAuthTokenRefresher?,
) : McpService, CoroutineScope by scope {
    private val transitionMutex: Mutex = Mutex()
    private val httpClient: HttpClient = scope.McpStreamableHttpClient()
    private val processClient: ProcessClient = scope.ProcessClient()
    private var serviceState: McpServiceState = McpServiceState()

    override val clients: StateFlow<Map<String, McpClient>>
        field = MutableStateFlow(emptyMap())
    override val authentication: StateFlow<Map<String, McpAuthenticationState>>
        field = MutableStateFlow(emptyMap())

    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                settings
                    .map { snapshot -> snapshot.mcpServers.enabledConfigurations() }
                    .distinctUntilChanged()
                    .collect { reconcileSettings() }
            } finally {
                releaseResources()
                this@McpServiceImpl.cancel()
            }
        }
    }

    /** Refreshes catalogs through every currently healthy client. */
    override suspend fun refresh() {
        coroutineContext.ensureActive()
        val owners = transitionMutex.withLock {
            serviceState.owners.values.toList()
        }
        owners.forEach { owner -> owner.refresh() }
    }

    override suspend fun invalidate(serverName: String) {
        coroutineContext.ensureActive()
        val retiredOwner = transitionMutex.withLock {
            val owner = serviceState.owners[serverName]
            serviceState = serviceState.copy(
                identities = serviceState.identities - serverName,
                owners = serviceState.owners - serverName,
            )
            clients.value = clients.value - serverName
            owner
        }
        withContext(NonCancellable) {
            retiredOwner?.close()
        }
        launch {
            reconcileSettings()
        }
    }

    /** Cancels this service; its lifecycle coroutine then closes every client owner. */
    override fun close() {
        cancel()
    }

    private suspend fun reconcileSettings() {
        authentication.value = settings.value.mcpServers.mapValues { (_, configuration) ->
            configuration.persistedAuthenticationState()
        }
        val reconciliation = transitionMutex.withLock {
            coroutineContext.ensureActive()
            val configurations = settings.value.mcpServers.enabledConfigurations()
            val identities = configurations.mapValues { (_, configuration) ->
                configuration.connectionIdentity()
            }
            if (identities == serviceState.identities) {
                serviceState.owners.forEach { (name, owner) ->
                    configurations[name]?.let(owner::updateConfiguration)
                }
                return@withLock McpReconciliation()
            }

            val previousOwners = serviceState.owners
            val runnableConfigurations = configurations.filterValues(
                McpServerConfiguration::hasRunnableAuthentication,
            )
            val retainedOwners = runnableConfigurations.mapNotNull { (name, configuration) ->
                previousOwners[name]
                    ?.takeIf { owner ->
                        owner.configuration.connectionIdentity() ==
                            configuration.connectionIdentity()
                    }
                    ?.also { owner -> owner.updateConfiguration(configuration) }
                    ?.let { owner -> name to owner }
            }.toMap()
            val retiredOwners = previousOwners
                .filter { (name, owner) -> retainedOwners[name] !== owner }
                .values
                .toList()
            val createdOwners = runnableConfigurations
                .filter { (name, _) -> name !in retainedOwners }
                .mapValues { (name, configuration) -> createOwner(name, configuration) }
            val nextOwners = runnableConfigurations.keys.associateWith { name ->
                retainedOwners[name] ?: checkNotNull(createdOwners[name])
            }
            val currentClients = clients.value
            val nextClients = configurations.mapValues { (name, configuration) ->
                if (!configuration.hasRunnableAuthentication()) {
                    McpAuthenticationBlockedClient(
                        serverName = name,
                        tools = currentClients[name]?.listTools().orEmpty(),
                    )
                } else {
                    val owner = checkNotNull(nextOwners[name])
                    if (retainedOwners[name] === owner) {
                        currentClients[name]
                            ?.takeUnless { it is McpAuthenticationBlockedClient }
                            ?: owner.client()
                    } else {
                        owner.client()
                    }
                }
            }

            serviceState = McpServiceState(
                identities = identities,
                owners = nextOwners,
            )
            clients.value = nextClients
            McpReconciliation(
                createdOwners = createdOwners.values.toList(),
                retiredOwners = retiredOwners,
            )
        }

        withContext(NonCancellable) {
            reconciliation.retiredOwners.forEach { owner -> owner.close() }
        }
        reconciliation.createdOwners.forEach { owner -> owner.reconnect() }
    }

    private fun createOwner(
        name: String,
        configuration: McpServerConfiguration,
    ): McpClientOwner {
        val authorizationMutex = Mutex()
        val serverHttpClient = if (configuration is McpServerConfiguration.StreamableHttp) {
            httpClient.withMcpAuthorization { forceRefresh ->
                authorizationMutex.withLock {
                    authorizationToken(name, forceRefresh)
                }
            }
        } else {
            null
        }
        return McpClientOwner(
            scope = this,
            serverName = name,
            initialConfiguration = configuration,
            openTransport = { configurationProvider ->
                configurationProvider().openTransport(serverHttpClient)
            },
            publishCatalog = ::publishCatalog,
            release = { serverHttpClient?.close() },
        )
    }

    private suspend fun McpServerConfiguration.openTransport(
        serverHttpClient: HttpClient?,
    ): Transport =
        when (this) {
            is McpServerConfiguration.StreamableHttp ->
                requireNotNull(serverHttpClient).openMcpStreamableHttpTransport(this)

            is McpServerConfiguration.Stdio -> processClient.openMcpStdioTransport(this)
        }

    private suspend fun authorizationToken(
        serverName: String,
        forceRefresh: Boolean,
    ): String? {
        val current = settings.value.mcpServers[serverName]
            as? McpServerConfiguration.StreamableHttp
            ?: return null
        val initialized = current.oauth as? McpOAuthConfiguration.Initialized ?: return null
        val expiresSoon = initialized.expiresAtEpochSeconds?.let { expiresAt ->
            expiresAt <= Clock.System.now().epochSeconds + OAuthRefreshSkewSeconds
        } ?: false
        if (!forceRefresh && !expiresSoon) return initialized.accessToken.value
        val store = configurationStore
        val refresher = tokenRefresher
        if (store == null || refresher == null) {
            if (forceRefresh) {
                updateAuthentication(
                    serverName,
                    McpAuthenticationState.ReauthorizationRequired,
                )
                return null
            }
            return initialized.accessToken.value
        }
        if (initialized.refreshToken == null) {
            if (forceRefresh) {
                updateAuthentication(
                    serverName,
                    McpAuthenticationState.ReauthorizationRequired,
                )
                return null
            }
            return initialized.accessToken.value
        }

        updateAuthentication(serverName, McpAuthenticationState.Refreshing)
        return try {
            val refreshed = refresher.refresh(initialized)
            val persisted = store.update { configurations ->
                val latest = configurations[serverName]
                    as? McpServerConfiguration.StreamableHttp
                    ?: return@update configurations
                if (latest.oauth != initialized) return@update configurations
                configurations + (serverName to latest.copy(oauth = refreshed))
            }
            val latest = persisted[serverName]
                as? McpServerConfiguration.StreamableHttp
            val token = (latest?.oauth as? McpOAuthConfiguration.Initialized)?.accessToken?.value
            updateAuthentication(
                serverName,
                if (token == null) {
                    McpAuthenticationState.ReauthorizationRequired
                } else {
                    McpAuthenticationState.Authorized
                },
            )
            token
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            updateAuthentication(serverName, McpAuthenticationState.Authorized)
            throw cancellation
        } catch (failure: Throwable) {
            updateAuthentication(
                serverName,
                McpAuthenticationState.Failed("Token refresh failed."),
            )
            throw failure
        }
    }

    private fun updateAuthentication(
        serverName: String,
        state: McpAuthenticationState,
    ) {
        authentication.value = authentication.value + (serverName to state)
    }

    private suspend fun publishCatalog(
        owner: McpClientOwner,
        catalog: McpClientCatalog,
    ): Boolean =
        transitionMutex.withLock {
            if (serviceState.owners[owner.serverName] !== owner) return@withLock false
            clients.value = clients.value + (owner.serverName to owner.client(catalog))
            true
        }

    private suspend fun releaseResources() {
        withContext(NonCancellable) {
            val owners = transitionMutex.withLock {
                serviceState.owners.values.toList().also {
                    serviceState = McpServiceState()
                    clients.value = emptyMap()
                    authentication.value = emptyMap()
                }
            }
            owners.forEach { owner -> owner.close() }
            processClient.close()
        }
    }
}

/** Creates an independently cancellable MCP service under this scope. */
public fun CoroutineScope.McpServiceImpl(
    settings: StateFlow<McpSettings>,
    configurationStore: McpConfigurationStore? = null,
    tokenRefresher: McpOAuthTokenRefresher? = null,
): McpServiceImpl {
    val serviceScope = supervisorChildScope()
    return try {
        McpServiceImpl(
            scope = serviceScope,
            settings = settings,
            configurationStore = configurationStore,
            tokenRefresher = tokenRefresher,
        )
    } catch (failure: Throwable) {
        serviceScope.cancel()
        throw failure
    }
}

private fun Map<String, McpServerConfiguration>.enabledConfigurations():
    Map<String, McpServerConfiguration> =
    entries
        .asSequence()
        .filter { (_, configuration) -> configuration.enabled }
        .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
        .associate(Map.Entry<String, McpServerConfiguration>::toPair)

private data class McpServiceState(
    val identities: Map<String, McpConnectionIdentity> = emptyMap(),
    val owners: Map<String, McpClientOwner> = emptyMap(),
)

private data class McpReconciliation(
    val createdOwners: List<McpClientOwner> = emptyList(),
    val retiredOwners: List<McpClientOwner> = emptyList(),
)

private sealed interface McpConnectionIdentity {
    data class StreamableHttp(
        val url: String,
        val headers: Map<String, io.github.stream29.kodex.mcp.contract.McpSecret>,
        val oauth: OAuth?,
    ) : McpConnectionIdentity

    data class Stdio(
        val configuration: McpServerConfiguration.Stdio,
    ) : McpConnectionIdentity

    data class OAuth(
        val client: io.github.stream29.kodex.mcp.contract.McpOAuthClient,
        val resource: String?,
        val scopes: List<String>,
        val initialized: Boolean,
        val authorizationEndpoint: String?,
        val tokenEndpoint: String?,
        val tokenEndpointAuthMethod:
            io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod?,
    )
}

private fun McpServerConfiguration.connectionIdentity(): McpConnectionIdentity =
    when (this) {
        is McpServerConfiguration.Stdio -> McpConnectionIdentity.Stdio(this)
        is McpServerConfiguration.StreamableHttp -> McpConnectionIdentity.StreamableHttp(
            url = url,
            headers = headers,
            oauth = oauth?.let { configured ->
                val initialized = configured as? McpOAuthConfiguration.Initialized
                McpConnectionIdentity.OAuth(
                    client = configured.client,
                    resource = configured.resource,
                    scopes = configured.scopes,
                    initialized = initialized != null,
                    authorizationEndpoint = initialized?.resolvedAuthorizationEndpoint,
                    tokenEndpoint = initialized?.resolvedTokenEndpoint,
                    tokenEndpointAuthMethod = initialized?.tokenEndpointAuthMethod,
                )
            },
        )
    }

private fun McpServerConfiguration.hasRunnableAuthentication(): Boolean =
    this !is McpServerConfiguration.StreamableHttp ||
        oauth == null ||
        oauth is McpOAuthConfiguration.Initialized

private fun McpServerConfiguration.persistedAuthenticationState(): McpAuthenticationState =
    when (this) {
        is McpServerConfiguration.Stdio -> McpAuthenticationState.NotConfigured
        is McpServerConfiguration.StreamableHttp -> when (oauth) {
            null -> McpAuthenticationState.NotConfigured
            is McpOAuthConfiguration.Uninitialized -> McpAuthenticationState.LoginRequired
            is McpOAuthConfiguration.Initialized -> McpAuthenticationState.Authorized
        }
    }

private const val OAuthRefreshSkewSeconds: Long = 30
