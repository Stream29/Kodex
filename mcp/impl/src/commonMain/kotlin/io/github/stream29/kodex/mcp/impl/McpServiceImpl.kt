package io.github.stream29.kodex.mcp.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.mcp.stdio.openMcpStdioTransport
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
import io.github.stream29.kodex.mcp.streamablehttp.openMcpStreamableHttpTransport
import io.github.stream29.kodex.utils.coroutines.runCatchingCancellable
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
 * The service observes complete settings snapshots, retains unchanged clients,
 * and replaces only clients whose server configuration changed. Its public
 * tool list is replaced only after one complete connection transition.
 */
public class McpServiceImpl internal constructor(
    scope: CoroutineScope,
    private val settings: StateFlow<McpSettings>,
) : McpService, CoroutineScope by scope {
    private val transitionMutex: Mutex = Mutex()
    private val httpClient: HttpClient = scope.McpStreamableHttpClient()
    private val processClient: ProcessClient = scope.ProcessClient()
    private var state: McpServiceState = McpServiceState()

    override val tools: StateFlow<List<McpTool>>
        field = MutableStateFlow(emptyList())

    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                settings
                    .map { snapshot -> snapshot.mcpServers.enabledConfigurations() }
                    .distinctUntilChanged()
                    .collect {
                        transition(refreshRetainedClients = false)
                    }
            } finally {
                releaseResources()
                this@McpServiceImpl.cancel()
            }
        }
    }

    /** Refreshes every configured server without reconnecting unchanged active clients. */
    override suspend fun refresh() {
        coroutineContext.ensureActive()
        transition(refreshRetainedClients = true)
    }

    /** Cancels this service; its lifecycle coroutine then closes all active clients. */
    override fun close() {
        cancel()
    }

    private suspend fun transition(
        refreshRetainedClients: Boolean,
    ) {
        val retiredClients = transitionMutex.withLock {
            coroutineContext.ensureActive()
            val configurations = settings.value.mcpServers.enabledConfigurations()
            if (!refreshRetainedClients && configurations == state.configurations) {
                return@withLock emptyList()
            }

            val previousClients = state.activeClients
            val (retainedClients, retiredClients) = previousClients.values.partition { active ->
                configurations[active.name] == active.configuration
            }
            val newConfigurations = configurations.entries
                .filter { (name, configuration) ->
                    previousClients[name]?.configuration != configuration
                }
                .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
            val activeClients = retainedClients.map { active ->
                if (refreshRetainedClients) active.refreshTools() else active
            }
            val createdClients = mutableListOf<ActiveMcpClient>()
            try {
                newConfigurations.forEach { (name, configuration) ->
                    connect(name, configuration).fold(
                        onSuccess = { client -> createdClients += client },
                        onFailure = { failure ->
                            logger.warn(failure) {
                                "Failed to connect MCP server $name."
                            }
                        },
                    )
                }
                val nextClients = (activeClients + createdClients)
                    .associateBy(ActiveMcpClient::name)
                val nextTools = nextClients.toMcpTools()
                state = McpServiceState(
                    configurations = configurations,
                    activeClients = nextClients,
                )
                tools.value = nextTools
                createdClients.clear()
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    createdClients.forEach { active ->
                        runCatching { active.client.close() }
                    }
                }
                throw failure
            }
            retiredClients
        }
        if (retiredClients.isEmpty()) return
        launch {
            withContext(NonCancellable) {
                retiredClients.forEach { active ->
                    runCatching { active.client.close() }
                }
            }
        }
    }

    private suspend fun connect(
        name: String,
        configuration: McpServerConfiguration,
    ): Result<ActiveMcpClient> {
        val client = Client(Implementation(name = "kodex", version = "0.2.1"))
        client.setNotificationHandler<ToolListChangedNotification>(
            Method.Defined.NotificationsToolsListChanged,
        ) {
            async { refreshServer(name, client) }
        }
        val transport: Transport = when (configuration) {
            is McpServerConfiguration.StreamableHttp ->
                httpClient.openMcpStreamableHttpTransport(configuration)

            is McpServerConfiguration.Stdio -> processClient.openMcpStdioTransport(configuration)
        }
        return runCatchingCancellable {
            try {
                client.connect(transport)
                ActiveMcpClient(
                    configuration = configuration,
                    name = name,
                    client = client,
                    instructions = client.serverInstructions.orEmpty(),
                    tools = client.listEveryTool(),
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { client.close() }
                }
                throw failure
            }
        }
    }

    private suspend fun ActiveMcpClient.refreshTools(): ActiveMcpClient {
        if (client.serverCapabilities?.tools == null) {
            return copy(tools = emptyList())
        }
        return runCatchingCancellable {
            copy(tools = client.listEveryTool())
        }.getOrElse { failure ->
            logger.warn(failure) { "Failed to refresh MCP server $name." }
            this
        }
    }

    private suspend fun refreshServer(name: String, client: Client) {
        transitionMutex.withLock {
            coroutineContext.ensureActive()
            val current = state.activeClients[name] ?: return@withLock
            if (current.client !== client) return@withLock
            val refreshed = current.refreshTools()
            val clients = state.activeClients + (name to refreshed)
            val nextTools = clients.toMcpTools()
            state = state.copy(activeClients = clients)
            tools.value = nextTools
        }
    }

    private suspend fun Client.listEveryTool(): List<io.modelcontextprotocol.kotlin.sdk.types.Tool> {
        if (serverCapabilities?.tools == null) return emptyList()
        return buildList {
            // `null` starts pagination and later marks its final page.
            var cursor: String? = null
            do {
                val page = listTools(ListToolsRequest(cursor?.let(::PaginatedRequestParams)))
                addAll(page.tools)
                cursor = page.nextCursor
            } while (cursor != null)
        }
    }

    private suspend fun releaseResources() {
        withContext(NonCancellable) {
            val clients = transitionMutex.withLock {
                state.activeClients.values.toList().also {
                    state = McpServiceState()
                    tools.value = emptyList()
                }
            }
            clients.forEach { active ->
                runCatching { active.client.close() }
            }
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
    filterValues(McpServerConfiguration::enabled)

private data class McpServiceState(
    val configurations: Map<String, McpServerConfiguration> = emptyMap(),
    val activeClients: Map<String, ActiveMcpClient> = emptyMap(),
)

private val logger by lazy {
    KotlinLogging.logger {}.global()
}
