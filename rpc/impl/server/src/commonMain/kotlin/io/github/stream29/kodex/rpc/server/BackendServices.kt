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

/**
 * Owns a complete backend graph without selecting a CLI or running Home migration.
 * Callers must provide a prepared Home; external client factories are test seams,
 * not frontend services. Every created client is closed after its consumers.
 * Settings/auth/model/global initialization does not acquire the Session
 * repository; its original host creates it on the first real Session/catalog access.
 */
public suspend fun <R> withBackendServices(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings = defaultBackendSettings(),
    createLoginClient: () -> LoginClient = { OpenAiLoginClient() },
    createClient: (OpenAiAuthStore) -> Client = { OpenAiClient(it) },
    createTitleGenerator: (Client) -> SessionTitleGenerator = { OpenAiSessionTitleGenerator(it) },
    block: suspend CoroutineScope.(BackendServices) -> R,
): R = coroutineScope {
    val owner = supervisorChildScope()
    var login: LoginClient? = null
    var credentials: BackendFileSystemAuthStore? = null
    var client: Client? = null
    var failure: Throwable? = null
    try {
        val store = openBackendSettings(home, defaults)
        val source = store.settings.map { it.authSource }.stateIn(owner, SharingStarted.Eagerly, store.settings.value.authSource)
        val loginClient = createLoginClient().also { login = it }
        val auth = owner.BackendFileSystemAuthStore(home, codexHome, source, loginClient).also { credentials = it }
        val api = createClient(auth).also { client = it }
        val mcpOAuth = owner.DefaultMcpOAuthClient()
        withBackendGlobalState(
            store, agentsHome, home, codexHome, api, mcpOAuth, BackendMcpOAuthBridge(mcpOAuth::prepare),
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
                }) { host ->
                    val runtime = BackendAgentRuntimeRpc(host, { store.settings.value.sessionTitle }, createTitleGenerator(api))
                    val management = BackendSessionManagement(host, runtime) {
                        BackendLogger.error(it) { "Suggested Session operation failed." }
                    }
                    val service = BackendGlobalRpc(host, global, account, oauth, management, runtime.notifications)
                    block(BackendServices(service, runtime, host))
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
public class BackendServices internal constructor(
    public val global: GlobalRpc,
    public val runtime: AgentRuntimeRpc,
    private val host: BackendSessionHost,
) {
    public fun register(server: RpcServer) {
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

/** Same baseline values as the original settings, without its frontend or control-Hook fields. */
public fun defaultBackendSettings(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell.default,
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

private val BackendLogger = KotlinLogging.logger {}
