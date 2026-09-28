package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.cli.settings.BackendSettingsStore
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.mcp.impl.McpServiceImpl
import io.github.stream29.kodex.mcp.impl.validateMcpConfigurationUpdate
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.openai.modelcatalog.ownedOpenAiModelCatalog
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path

/**
 * Backend resources for the settings/models/MCP slice of GlobalRpc.
 * The OpenAI client is borrowed from the backend authentication owner.
 * No frontend owns or closes these resources, and no partial GlobalRpc is registered.
 */
public suspend fun <R> withBackendGlobalState(
    store: BackendSettingsStore,
    agentsHome: Path,
    kodexHome: Path,
    codexHome: Path,
    client: OpenAiClient,
    tokenRefresher: McpOAuthTokenRefresher,
    loginAttemptFactory: McpOAuthLoginAttemptFactory,
    block: suspend CoroutineScope.(BackendGlobalState) -> R,
): R = withBackendGlobalState(
    store, agentsHome, kodexHome, codexHome, client, loginAttemptFactory,
    serviceFactory = { settings, configurations ->
        McpServiceImpl(settings, configurations, tokenRefresher)
    },
    block = block,
)

internal suspend fun <R> withBackendGlobalState(
    store: BackendSettingsStore,
    agentsHome: Path,
    kodexHome: Path,
    codexHome: Path,
    client: OpenAiClient,
    loginAttemptFactory: McpOAuthLoginAttemptFactory,
    serviceFactory: CoroutineScope.(StateFlow<McpSettings>, McpConfigurationStore) -> McpService,
    block: suspend CoroutineScope.(BackendGlobalState) -> R,
): R = coroutineScope {
    val owner = supervisorChildScope()
    var service: McpService? = null
    var manager: McpManagerImpl? = null
    var models: OpenAiModelCatalog? = null
    var failure: Throwable? = null
    try {
        val configurations = BackendMcpConfigurationStore(store, owner)
        val mcpSettings = store.settings.project(owner) { McpSnapshot(it.mcpServers) }
        val context = store.settings.project(owner) {
            ContextSnapshot(agentsHome, kodexHome, codexHome, it)
        }
        service = owner.serviceFactory(mcpSettings, configurations)
        manager = owner.McpManagerImpl(
            configurations, service,
            codexImportSource = { readBackendCodexMcpSettings(codexHome) },
            loginAttemptFactory = loginAttemptFactory,
        )
        models = owner.ownedOpenAiModelCatalog(client)
        coroutineScope {
            block(BackendGlobalState(store, configurations, context, service, manager, models, codexHome))
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        closeBackendResources(
            failure,
            { manager?.close() },
            { service?.close() },
            { models?.close() },
            { owner.cancelAndJoin() },
        )
    }
}

public class BackendGlobalState internal constructor(
    public val store: BackendSettingsStore,
    public val mcpConfigurations: McpConfigurationStore,
    public val contextSettings: StateFlow<AgentContextSettings>,
    public val mcpService: McpService,
    public val mcpManager: McpManagerImpl,
    public val modelCatalog: OpenAiModelCatalog,
    private val codexHome: Path,
) {
    public val settings: StateFlow<BackendSettings> get() = store.settings
    public val models: StateFlow<List<ModelInfo>> get() = modelCatalog.models
    public val mcpServers: StateFlow<List<McpManagedServerState>> get() = mcpManager.servers

    public suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean =
        mcpManager.commitConfigurationChange(expect.mcpServers, update.mcpServers) {
            store.compareAndSet(expect, update) { current, proposed ->
                validateMcpConfigurationUpdate(current.mcpServers, proposed.mcpServers)
            }
        }

    public suspend fun reconnectMcpServer(name: String): Unit = mcpManager.reconnect(name)

    public suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
        readBackendCodexMcpSettings(codexHome)
}

/** Token refresh and manager edits share the very same file update lock as CAS. */
internal class BackendMcpConfigurationStore(
    private val store: BackendSettingsStore,
    owner: CoroutineScope,
) : McpConfigurationStore {
    override val configurations: StateFlow<Map<String, McpServerConfiguration>> =
        store.settings.project(owner) { it.mcpServers }

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> =
        store.update { current -> current.copy(mcpServers = transform(current.mcpServers)) }.mcpServers
}

private fun <T, R> StateFlow<T>.project(owner: CoroutineScope, transform: (T) -> R): StateFlow<R> =
    map(transform).stateIn(owner, SharingStarted.Eagerly, transform(value))

private data class McpSnapshot(override val mcpServers: Map<String, McpServerConfiguration>) : McpSettings

private data class ContextSnapshot(
    override val agentsHome: Path,
    override val kodexHome: Path,
    override val codexHome: Path,
    private val settings: BackendSettings,
) : AgentContextSettings {
    override val shell get() = settings.shell
    override val sources get() = settings.contextSources
}
