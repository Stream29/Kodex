@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.rpc.client.FrontendSettingsAccess
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.client.rpcStateIn
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion

/**
 * One editing intent keeps its target's initial value. Only false is retried; a lost response,
 * cancellation, or expired owner is never interpreted as permission to replay a write.
 */
public actual suspend fun <T, F> SuspendMutableStateFlow<T>.editField(
    initial: F,
    select: (T) -> F,
    replace: (T) -> T,
    checkOwner: () -> Unit,
): Boolean {
    while (true) {
        currentCoroutineContext().ensureActive()
        checkOwner()
        val expect = value
        if (select(expect) != initial) return false
        if (compareAndSet(expect, replace(expect))) return true
        delay(50)
    }
}

public actual class RpcGlobalSettings private constructor(
    public actual val settings: SuspendMutableStateFlow<BackendSettings>,
    public actual val models: StateFlow<List<ModelInfo>>,
    public actual val authentication: StateFlow<SettingsAuthenticationState>,
    public actual val usage: StateFlow<SettingsAccountUsageState>,
    public actual val mcp: StateFlow<List<McpManagedServerState>>,
    public actual val frontend: FrontendSettingsAccess,
    public actual val sidebarWidths: StateFlow<Pair<Int, Int>>,
    private val widths: MutableStateFlow<Pair<Int, Int>>,
    private val owner: Job,
) : AutoCloseable {
    private val mutableOperationFailure = MutableStateFlow(false)
    internal val operationFailure: StateFlow<Boolean> = mutableOperationFailure.asStateFlow()
    public actual fun ensureActive(): Unit = owner.ensureActive()
    internal fun reportOperationFailure(error: Throwable) {
        SettingsOperationLogger.error { "A Settings operation failed (${error::class.simpleName ?: "unknown"})." }
        mutableOperationFailure.value = true
    }
    internal fun dismissOperationFailure() { mutableOperationFailure.value = false }

    public actual suspend fun <F> edit(
        initial: F,
        select: (BackendSettings) -> F,
        replace: (BackendSettings) -> BackendSettings,
    ): Boolean = settings.editField(initial, select, replace, ::ensureActive)

    public actual fun resizeSidebars(left: Int, right: Int) {
        ensureActive()
        require(left >= 0 && right >= 0)
        widths.value = left to right
    }

    public actual suspend fun updateHooks(hooks: List<NotificationHook>) {
        ensureActive()
        frontend.update { it.copy(hooks = hooks) }
    }

    public actual override fun close() { owner.cancel() }
    public actual suspend fun join(): Unit = owner.join()

    public actual companion object {
        public actual suspend fun open(
            rpc: GlobalRpc,
            scope: CoroutineScope,
            applicationWidth: Int,
        ): RpcGlobalSettings {
            require(applicationWidth >= 0)
            // Subscription failure invalidates all editing against this owner and reaches the
            // application. StateFlow collectors alone cannot observe upstream failure.
            val owner = Job(scope.coroutineContext[Job])
            val local = CoroutineScope(scope.coroutineContext + owner)
            try {
                val settings = local.rpcStateIn(rpc::getSettings) { rpc.getSettingsFlow().required() }
                val models = local.rpcStateIn(rpc::getModels) { rpc.getModelsFlow().required() }
                val auth = local.rpcStateIn(rpc::getAuthentication) { rpc.getAuthenticationFlow().required() }
                val usage = local.rpcStateIn(rpc::getAccountUsage) { rpc.getAccountUsageFlow().required() }
                val mcp = local.rpcStateIn(rpc::getMcpServers) { rpc.getMcpServersFlow().required() }
                val widths = MutableStateFlow(applicationWidth / 4 to applicationWidth / 4)
                return RpcGlobalSettings(
                    settings.asSuspendMutableStateFlow { expect, update ->
                        owner.ensureActive()
                        rpc.compareAndSetSettings(expect, update)
                    }, models, auth, usage, mcp, RpcFrontendSettings.open(rpc, local), widths, widths, owner,
                )
            } catch (error: Throwable) {
                owner.cancel()
                throw error
            }
        }
    }
}

private fun <T> Flow<T>.required(): Flow<T> = onCompletion { cause ->
    // A transport may throw an ordinary shutdown error after this observer is
    // already cancelled. Preserve owner/caller cancellation instead of failing
    // the whole host during disposal. Live observation failures still propagate.
    currentCoroutineContext().ensureActive()
    if (cause == null) error("A required global observation ended.")
}

private val SettingsOperationLogger = KotlinLogging.logger {}
