package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.stdio.openMcpStdioTransport
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
import io.github.stream29.kodex.mcp.streamablehttp.openMcpStreamableHttpTransport
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
) : McpService, CoroutineScope by scope {
    private val transitionMutex: Mutex = Mutex()
    private val httpClient: HttpClient = scope.McpStreamableHttpClient()
    private val processClient: ProcessClient = scope.ProcessClient()
    private var serviceState: McpServiceState = McpServiceState()

    override val clients: StateFlow<Map<String, McpClient>>
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

    /** Cancels this service; its lifecycle coroutine then closes every client owner. */
    override fun close() {
        cancel()
    }

    private suspend fun reconcileSettings() {
        val reconciliation = transitionMutex.withLock {
            coroutineContext.ensureActive()
            val configurations = settings.value.mcpServers.enabledConfigurations()
            if (configurations == serviceState.configurations) {
                return@withLock McpReconciliation()
            }

            val previousOwners = serviceState.owners
            val retainedOwners = configurations.mapNotNull { (name, configuration) ->
                previousOwners[name]
                    ?.takeIf { owner -> owner.configuration == configuration }
                    ?.let { owner -> name to owner }
            }.toMap()
            val retiredOwners = previousOwners
                .filter { (name, owner) -> retainedOwners[name] !== owner }
                .values
                .toList()
            val createdOwners = configurations
                .filter { (name, _) -> name !in retainedOwners }
                .mapValues { (name, configuration) -> createOwner(name, configuration) }
            val nextOwners = configurations.keys.associateWith { name ->
                retainedOwners[name] ?: checkNotNull(createdOwners[name])
            }
            val currentClients = clients.value
            val nextClients = nextOwners.mapValues { (name, owner) ->
                if (retainedOwners[name] === owner) {
                    currentClients[name] ?: owner.client()
                } else {
                    owner.client()
                }
            }

            serviceState = McpServiceState(
                configurations = configurations,
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
    ): McpClientOwner =
        McpClientOwner(
            scope = this,
            serverName = name,
            configuration = configuration,
            openTransport = { configuration.openTransport() },
            publishCatalog = ::publishCatalog,
        )

    private suspend fun McpServerConfiguration.openTransport(): Transport =
        when (this) {
            is McpServerConfiguration.StreamableHttp ->
                httpClient.openMcpStreamableHttpTransport(this)

            is McpServerConfiguration.Stdio -> processClient.openMcpStdioTransport(this)
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
): McpServiceImpl {
    val serviceScope = supervisorChildScope()
    return try {
        McpServiceImpl(
            scope = serviceScope,
            settings = settings,
        )
    } catch (failure: Throwable) {
        serviceScope.cancel()
        throw failure
    }
}

private fun Map<String, McpServerConfiguration>.enabledConfigurations(): Map<String, McpServerConfiguration> =
    entries
        .asSequence()
        .filter { (_, configuration) -> configuration.enabled }
        .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
        .associate(Map.Entry<String, McpServerConfiguration>::toPair)

private data class McpServiceState(
    val configurations: Map<String, McpServerConfiguration> = emptyMap(),
    val owners: Map<String, McpClientOwner> = emptyMap(),
)

private data class McpReconciliation(
    val createdOwners: List<McpClientOwner> = emptyList(),
    val retiredOwners: List<McpClientOwner> = emptyList(),
)
