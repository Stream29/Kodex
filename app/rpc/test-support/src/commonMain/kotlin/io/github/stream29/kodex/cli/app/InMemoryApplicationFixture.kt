package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.migration.KodexHomeHandle
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as ModelClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.rpc.RpcClient

private class InMemoryFrontendConnection(private val client: RpcClient) : FrontendRpcConnection, RpcClient by client {
    override val state = MutableStateFlow(BackendConnectionState.Connected)
    override val failureReason = MutableStateFlow<String?>(null)
    override val available: Boolean get() = true
    override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit) {
        // Scoped in-memory transport has no network reconnection. Session recovery remains real.
    }
}

public actual suspend fun <R> withKodexApplication(
    homeHandle: KodexHomeHandle,
    workingDirectory: Path,
    codexDirectory: Path,
    agentsDirectory: Path,
    applicationWidth: Int?,
    createClient: (OpenAiAuthStore) -> ModelClient,
    createLoginClient: () -> LoginClient,
    onPhase: suspend (ApplicationStartupPhase) -> Unit,
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R {
    var primary: Throwable? = null
    try {
        onPhase(ApplicationStartupPhase.HomePrepared)
        val cwd = SystemCoroutineFileSystem.resolve(workingDirectory)
        return withBackendServices(
            homeHandle.home, resolveAllowingMissing(codexDirectory), resolveAllowingMissing(agentsDirectory),
            createClient = createClient, createLoginClient = createLoginClient,
        ) { backend ->
            onPhase(ApplicationStartupPhase.BackendReady)
            val hooksOwner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            hooksOwner.launch(start = CoroutineStart.UNDISPATCHED) {
                collectNotificationHooks(backend.notifications, { backend.settings.unified.current.backend.hooks }, homeHandle.home)
            }
            try {
                withInMemoryRpc(registerServices = { backend.register(this) }) { client ->
                    onPhase(ApplicationStartupPhase.ServicesRegistered)
                    withFrontendApplication(InMemoryFrontendConnection(client), cwd, NativeFrontendHost,
                        io.github.stream29.kodex.cli.rpc.LocalRpcFrontendEnvironment, applicationWidth) { app ->
                        onPhase(ApplicationStartupPhase.FrontendReady)
                        onPhase(ApplicationStartupPhase.RendererStarted)
                        render(app)
                    }
                }
            } finally {
                withContext(NonCancellable) { hooksOwner.coroutineContext.job.cancelAndJoin() }
            }
        }
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { withContext(NonCancellable) { homeHandle.closeAndJoin() } }
        catch (error: Throwable) { if (primary == null) throw error else primary.addSuppressed(error) }
    }
}

private suspend fun resolveAllowingMissing(path: Path): Path {
    if (SystemCoroutineFileSystem.metadataOrNull(path) != null) return SystemCoroutineFileSystem.resolve(path)
    val parent = path.parent
    return Path(if (parent == null) SystemCoroutineFileSystem.resolve(Path(".")) else resolveAllowingMissing(parent), path.name)
}
