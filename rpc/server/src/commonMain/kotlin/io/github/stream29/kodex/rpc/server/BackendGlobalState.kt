@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agenteventbus.BackendSignalKeys
import io.github.stream29.kodex.agenteventbus.SignalDelivery
import io.github.stream29.kodex.agenteventbus.publishSignal
import io.github.stream29.kodex.agenteventbus.subscribeSignals
import io.github.stream29.kodex.cli.settings.BackendSettingsStore
import io.github.stream29.kodex.cli.settings.UnifiedSettingsStore
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.mcp.impl.McpOAuthLoginPreparer
import io.github.stream29.kodex.mcp.impl.McpServiceImpl
import io.github.stream29.kodex.mcp.impl.validateMcpConfigurationUpdate
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import io.github.stream29.kodex.openai.modelcatalog.ownedOpenAiModelCatalog
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/**
 * Backend resources for the settings/models/MCP slice of GlobalRpc.
 * The OpenAI client is borrowed from the backend authentication owner.
 * No frontend owns or closes these resources, and no partial GlobalRpc is registered.
 */
internal suspend fun <R> withBackendGlobalState(
    unified: UnifiedSettingsStore,
    agentsHome: Path,
    kodexHome: Path,
    codexHome: Path,
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
    tokenRefresher: McpOAuthTokenRefresher,
    loginPreparer: McpOAuthLoginPreparer,
    broker: MqBroker,
    block: suspend CoroutineScope.(BackendGlobalState) -> R,
): R = withBackendGlobalState(
    unified = unified,
    agentsHome = agentsHome,
    kodexHome = kodexHome,
    codexHome = codexHome,
    client = client,
    authStore = authStore,
    loginPreparer = loginPreparer,
    broker = broker,
    serviceFactory = { settings, configurations ->
        McpServiceImpl(settings, configurations, tokenRefresher)
    },
    block = block,
)

internal suspend fun <R> withBackendGlobalState(
    unified: UnifiedSettingsStore,
    agentsHome: Path,
    kodexHome: Path,
    codexHome: Path,
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
    loginPreparer: McpOAuthLoginPreparer,
    serviceFactory: CoroutineScope.(StateFlow<McpSettings>, McpConfigurationStore) -> McpService,
    broker: MqBroker,
    block: suspend CoroutineScope.(BackendGlobalState) -> R,
): R = coroutineScope {
    val store = unified.backendStore
    val owner = supervisorChildScope()
    var service: McpService? = null
    var manager: McpManagerImpl? = null
    var models: OpenAiModelCatalogStore? = null
    var failure: Throwable? = null
    try {
        val signals = BackendGlobalSignals(broker)
        val configurations = BackendMcpConfigurationStore(store, owner, signals)
        val mcpSettings = store.settings.project(owner) { McpSnapshot(it.mcpServers) }
        val context = store.settings.project(owner) {
            ContextSnapshot(agentsHome, kodexHome, codexHome, it)
        }
        service = owner.serviceFactory(mcpSettings, configurations)
        manager = owner.McpManagerImpl(
            store = configurations,
            service = service,
            loginPreparer = loginPreparer,
        )
        models = owner.ownedOpenAiModelCatalog(client, authStore)
        val global = BackendGlobalState(unified, configurations, context, service, manager, models, codexHome, signals)
        signals.observe(owner, global.models, BackendSignalKeys.GlobalModels)
        signals.observe(owner, global.mcpServers, BackendSignalKeys.GlobalMcpServers)
        signals.observeExternalSettings(owner, global::reloadFromBroadcast)
        coroutineScope {
            block(global)
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

public actual class BackendGlobalState internal constructor(
    public actual val unified: UnifiedSettingsStore,
    internal val mcpConfigurations: McpConfigurationStore,
    internal val contextSettings: StateFlow<AgentContextSettings>,
    internal val mcpService: McpService,
    internal val mcpManager: McpManagerImpl,
    internal val modelCatalog: OpenAiModelCatalogStore,
    private val codexHome: Path,
    private val signals: BackendGlobalSignals,
) {
    internal val store: BackendSettingsStore get() = unified.backendStore
    internal val settings: StateFlow<BackendSettings> get() = store.settings
    internal val models: StateFlow<List<ModelInfo>> get() = modelCatalog.models
    internal val mcpServers: StateFlow<List<McpManagedServerState>> get() = mcpManager.servers

    internal suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean =
        mcpManager.commitConfigurationChange(expect.mcpServers, update.mcpServers) {
            store.compareAndSet(expect, update) { current, proposed ->
                validateMcpConfigurationUpdate(current.mcpServers, proposed.mcpServers)
            }.also { accepted ->
                if (accepted && expect != update) signals.publish(BackendSignalKeys.GlobalSettingsBackend)
            }
        }

    internal suspend fun compareAndSetFrontendSettings(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean =
        unified.compareAndSetFrontend(expect, update).also { accepted ->
            if (accepted && expect != update) signals.publish(BackendSignalKeys.GlobalSettingsFrontend)
        }

    internal fun observeAccountChanges(owner: CoroutineScope, account: BackendAccountState) {
        signals.observe(owner, account.authentication, BackendSignalKeys.GlobalAuthentication)
        signals.observe(owner, account.accountUsage, BackendSignalKeys.GlobalAccountUsage)
    }

    internal suspend fun reconnectMcpServer(name: String): Unit = mcpManager.reconnect(name)

    public actual suspend fun reloadSettings(): Unit = reloadSettings(publish = true)

    internal suspend fun reloadFromBroadcast(): Unit = reloadSettings(publish = false)

    private suspend fun reloadSettings(publish: Boolean) {
        val expected = unified.current
        val candidate = unified.readCandidate()
        val accepted = mcpManager.commitConfigurationChange(expected.backend.mcpServers, candidate.backend.mcpServers) {
            unified.acceptReload(expected, candidate) { before, after ->
                validateMcpConfigurationUpdate(before.mcpServers, after.mcpServers)
            }
        }
        check(accepted) { "Settings changed during reload; retry the explicit reload." }
        if (publish) {
            if (expected.backend != candidate.backend) signals.publish(BackendSignalKeys.GlobalSettingsBackend)
            if (expected.frontend != candidate.frontend) signals.publish(BackendSignalKeys.GlobalSettingsFrontend)
        }
    }

    internal suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
        readBackendCodexMcpSettings(codexHome)
}

/** Token refresh and manager edits share the very same file update lock as CAS. */
internal class BackendMcpConfigurationStore(
    private val store: BackendSettingsStore,
    owner: CoroutineScope,
    private val signals: BackendGlobalSignals,
) : McpConfigurationStore {
    override val configurations: StateFlow<Map<String, McpServerConfiguration>> =
        store.settings.project(owner) { it.mcpServers }

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> {
        var changed = false
        val next = store.update { current ->
            val updated = transform(current.mcpServers)
            changed = updated != current.mcpServers
            current.copy(mcpServers = updated)
        }
        if (changed) signals.publish(BackendSignalKeys.GlobalSettingsBackend)
        return next.mcpServers
    }
}

internal class BackendGlobalSignals(private val broker: MqBroker) {
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private val origin = Uuid.random().toString()

    suspend fun publish(key: String) {
        try {
            broker.publishSignal(key, mapOf(MqHeaders.OriginId to origin))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GlobalSignalLogger.warn(error) { "Could not publish global invalidation $key." }
        }
    }

    fun <T> observe(owner: CoroutineScope, state: StateFlow<T>, key: String) {
        var previous = state.value
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            state.collect { next ->
                if (next != previous) {
                    previous = next
                    publish(key)
                }
            }
        }
    }

    fun observeExternalSettings(owner: CoroutineScope, reload: suspend () -> Unit) {
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            broker.subscribeSignals(setOf(BackendSignalKeys.AllGlobalSettings), DefaultMqTopicBufferCapacity).collect { delivery ->
                if (delivery is SignalDelivery.Signal && delivery.headers[MqHeaders.OriginId] == origin) return@collect
                try {
                    reload()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    GlobalSignalLogger.warn(error) { "Could not reload settings after a global invalidation." }
                }
            }
        }
    }
}

private val GlobalSignalLogger = KotlinLogging.logger {}

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
