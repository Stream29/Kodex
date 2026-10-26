package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

internal class BrowserPeer(val client: RpcClient, val alive: () -> Boolean)

internal class BrowserConnection : FrontendRpcConnection {
    private val active = MutableStateFlow<BrowserPeer?>(null)
    private val status = MutableStateFlow(BackendConnectionState.Reconnecting)
    private val failure = MutableStateFlow<String?>(null)
    override val state = status.asStateFlow()
    override val failureReason = failure.asStateFlow()
    override val available: Boolean get() = active.value?.alive?.invoke() == true
    private var recovery: suspend (RpcClient, () -> Boolean) -> Unit = { _, _ -> }
    private var everConnected = false

    override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit) { recovery = handler }

    suspend fun install(peer: BrowserPeer) {
        check(active.value == null)
        check(status.value != BackendConnectionState.Failed) { "Browser connection is terminal." }
        if (everConnected) recovery(peer.client, peer.alive)
        check(peer.alive()) { "Backend closed before connection publication." }
        check(status.value != BackendConnectionState.Failed) { "Browser connection was rejected during recovery." }
        active.value = peer
        everConnected = true
        status.value = BackendConnectionState.Connected
    }

    fun detach(peer: BrowserPeer) {
        if (active.value === peer) {
            active.value = null
            status.value = BackendConnectionState.Reconnecting
        }
    }

    fun reject() {
        active.value = null
        failure.value = "Backend rejected this connection. Re-import its current private connection record."
        status.value = BackendConnectionState.Failed
    }

    override suspend fun <T> call(call: RpcCall): T {
        currentCoroutineContext().ensureActive()
        val peer = active.value?.takeIf { it.alive() }
            ?: throw BackendConnectionLost("Disconnected; operation was not submitted.")
        return try { peer.client.call(call) }
        catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!peer.alive()) throw BackendConnectionLost("Backend transport closed; operation outcome may be unknown.", failure)
            throw failure
        }
    }

    override fun <T> callServerStreaming(call: RpcCall): Flow<T> = channelFlow {
        active.collectLatest { peer ->
            if (peer != null) {
                try { peer.client.callServerStreaming<T>(call).collect { send(it) } }
                catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (peer.alive() && active.value === peer) throw failure
                }
            }
        }
    }.buffer(0)
}
