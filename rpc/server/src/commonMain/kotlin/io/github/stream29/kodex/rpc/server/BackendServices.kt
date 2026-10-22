@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.utils.shellclient.default

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.impl.DefaultMcpOAuthClient
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as Client
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.files.Path
import kotlinx.rpc.RpcServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns a complete backend graph without selecting a CLI or running Home migration.
 * Callers must provide a prepared Home; external client factories are test seams,
 * not frontend services. Every created client is closed after its consumers.
 * Settings/auth/model/global initialization does not acquire the Session
 * repository; its original host creates it on the first real Session/catalog access.
 */
public actual suspend fun <R> withBackendServices(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings,
    createLoginClient: () -> LoginClient,
    createClient: (OpenAiAuthStore) -> Client,
    createTitleGenerator: (Client) -> SessionTitleGenerator,
    renewBackend: suspend () -> Unit,
    backendRenewalInterval: () -> Duration,
    checkAdmission: () -> Unit,
    block: suspend CoroutineScope.(BackendServices) -> R,
): R = coroutineScope {
    val owner = supervisorChildScope()
    var login: LoginClient? = null
    var credentials: BackendFileSystemAuthStore? = null
    var client: Client? = null
    var failure: Throwable? = null
    try {
        val unified = UnifiedSettingsStore.open(home, defaults)
        val store = unified.backendStore
        val source = store.settings.map { it.authSource }.stateIn(owner, SharingStarted.Eagerly, store.settings.value.authSource)
        val loginClient = createLoginClient().also { login = it }
        val auth = owner.BackendFileSystemAuthStore(home, codexHome, source, loginClient).also { credentials = it }
        val api = createClient(auth).also { client = it }
        val mcpOAuth = owner.DefaultMcpOAuthClient()
        withBackendGlobalState(
            store, unified, agentsHome, home, codexHome, api, auth, mcpOAuth, BackendMcpOAuthBridge(mcpOAuth::prepare),
        ) { global ->
            val account = BackendAccountState(owner, auth, api)
            val oauth = BackendOAuth(owner, auth, loginClient, global.mcpManager)
            var operationFailure: Throwable? = null
            try {
                val dependencies = KodexAgentDependencies(
                    api, global.modelCatalog, global.contextSettings, global.contextSettings,
                    global.mcpService,
                )
                withBackendSessionHost({
                    FileSystemKodexSessionRepository(home, dependencies)
                }, renewBackend, backendRenewalInterval, checkAdmission) { host ->
                    val runtime = BackendAgentRuntimeRpc(host, { store.settings.value.sessionTitle }, createTitleGenerator(api))
                    val management = BackendSessionManagement(host, runtime, { store.settings.value.newSession }) {
                        BackendLogger.error(it) { "Suggested Session operation failed." }
                    }
                    val service = BackendGlobalRpc(host, global, account, oauth, management, runtime.notifications)
                    block(BackendServices(service, runtime, host, global, runtime.notifications.flow))
                }
            } catch (error: Throwable) {
                operationFailure = error
                throw error
            } finally {
                closeBackendResources(operationFailure, { oauth.closeAndJoin() }, { account.closeAndJoin() })
            }
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        closeBackendResources(
            failure,
            { credentials?.close() },
            { owner.cancelAndJoin() },
            { client?.close() },
            { login?.close() },
        )
    }
}

/** Registered exactly once per shared connection; the graph remains owned by its host. */
public actual class BackendServices internal constructor(
    public actual val global: GlobalRpc,
    public actual val runtime: AgentRuntimeRpc,
    private val host: BackendSessionHost,
    public actual val settings: BackendGlobalState,
    public actual val notifications: kotlinx.coroutines.flow.Flow<io.github.stream29.kodex.rpc.models.Notification>,
) {
    public actual fun register(server: RpcServer) {
        server.registerService(GlobalRpc::class) { global }
        server.registerService(AgentRuntimeRpc::class) { runtime }
        server.registerService(IndexTimelineRpc::class) { BackendIndexTimelineRpc(host) }
        server.registerService(WorkTimelineRpc::class) { BackendWorkTimelineRpc(host) }
        server.registerService(SettingsTimelineRpc::class) { BackendSettingsTimelineRpc(host) }
        server.registerService(TimestampTimelineRpc::class) { BackendTimestampTimelineRpc(host) }
        server.registerService(TokenCountTimelineRpc::class) { BackendTokenCountTimelineRpc(host) }
        server.registerService(UnstableTimelineRpc::class) { BackendUnstableTimelineRpc(host) }
    }
}

public actual fun defaultBackendSettings(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell.default,
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

private val BackendLogger = KotlinLogging.logger {}
