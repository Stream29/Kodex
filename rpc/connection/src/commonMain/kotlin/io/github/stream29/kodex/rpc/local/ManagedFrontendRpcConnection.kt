@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

public actual class ManagedFrontendRpcConnection actual constructor() : FrontendRpcConnection {
    private val active = MutableStateFlow<ActiveRpcPeer?>(null)
    private val status = MutableStateFlow(BackendConnectionState.Reconnecting)
    private val failure = MutableStateFlow<String?>(null)
    actual override val state: StateFlow<BackendConnectionState> = status.asStateFlow()
    actual override val failureReason: StateFlow<String?> = failure.asStateFlow()
    actual override val available: Boolean get() = active.value?.alive?.invoke() == true
    private var recovery: suspend (RpcClient, () -> Boolean) -> Unit = { _, _ -> }
    private var everConnected = false

    actual override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit) {
        recovery = handler
    }

    public actual suspend fun install(peer: ActiveRpcPeer) {
        check(active.value == null)
        check(status.value != BackendConnectionState.Failed) { "Frontend connection is terminal." }
        if (everConnected) recovery(peer.client, peer.alive)
        check(peer.alive()) { "Backend closed before connection publication." }
        check(status.value != BackendConnectionState.Failed) { "Frontend connection was rejected during recovery." }
        active.value = peer
        everConnected = true
        status.value = BackendConnectionState.Connected
    }

    public actual fun detach(peer: ActiveRpcPeer) {
        if (active.value === peer) {
            active.value = null
            status.value = BackendConnectionState.Reconnecting
        }
    }

    public actual fun fail(reason: String?) {
        active.value = null
        failure.value = reason
        status.value = BackendConnectionState.Failed
    }

    actual override fun bindOperation(): RpcClient {
        val peer = active.value?.takeIf { it.alive() }
            ?: throw BackendConnectionLost("Backend is disconnected; operation was not submitted.")
        fun ensurePeer() {
            if (active.value !== peer || !peer.alive()) {
                throw BackendConnectionLost("Original backend transport closed; operation outcome may be unknown.")
            }
        }
        return object : RpcClient {
            override suspend fun <T> call(call: RpcCall): T {
                currentCoroutineContext().ensureActive()
                ensurePeer()
                return peer.client.call(call)
            }

            override fun <T> callServerStreaming(call: RpcCall): Flow<T> = channelFlow {
                ensurePeer()
                val watch = launch {
                    active.first { it !== peer }
                    throw BackendConnectionLost("Original backend transport closed; operation outcome may be unknown.")
                }
                try {
                    peer.client.callServerStreaming<T>(call).collect { send(it) }
                } finally {
                    watch.cancel()
                }
            }.buffer(0)
        }
    }

    actual override suspend fun <T> call(call: RpcCall): T {
        currentCoroutineContext().ensureActive()
        val peer = active.value?.takeIf { it.alive() }
            ?: throw BackendConnectionLost("Backend is disconnected; operation was not submitted.")
        return try {
            peer.client.call(call)
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!peer.alive()) {
                throw BackendConnectionLost("Backend transport closed; operation outcome may be unknown.", failure)
            }
            throw failure
        }
    }

    actual override fun <T> callServerStreaming(call: RpcCall): Flow<T> = channelFlow {
        active.collectLatest { peer ->
            if (peer != null) {
                try {
                    peer.client.callServerStreaming<T>(call).collect { send(it) }
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (peer.alive() && active.value === peer) throw failure
                }
            }
        }
    }.buffer(0)
}
