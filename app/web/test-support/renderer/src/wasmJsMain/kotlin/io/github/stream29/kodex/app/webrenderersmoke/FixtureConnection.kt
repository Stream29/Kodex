@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

internal class FixturePeer(
    val client: RpcClient,
    val alive: () -> Boolean,
    val close: suspend () -> Unit,
)

internal data class FixtureWriteAttempts(val appends: Int = 0, val resumes: Int = 0)

/** Test-host routing with the same no-write-retry boundary as the Native connection. */
internal class FixtureConnection : FrontendRpcConnection {
    private val active = MutableStateFlow<FixturePeer?>(null)
    private val status = MutableStateFlow(BackendConnectionState.Reconnecting)
    override val state = status.asStateFlow()
    override val failureReason = MutableStateFlow<String?>(null).asStateFlow()
    override val available: Boolean get() = active.value?.alive?.invoke() == true
    private var recovery: suspend (RpcClient, () -> Boolean) -> Unit = { _, _ -> }
    private var everConnected = false
    private val replacements = MutableStateFlow(0)
    val generation = replacements.asStateFlow()
    private val discarded = MutableStateFlow(0)
    val discardedReplies = discarded.asStateFlow()
    private val attempts = MutableStateFlow(FixtureWriteAttempts())
    val writeAttempts = attempts.asStateFlow()
    var discardNextAppendReply = false

    override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit) { recovery = handler }

    suspend fun install(peer: FixturePeer) {
        check(active.value == null)
        if (everConnected) recovery(peer.client, peer.alive)
        check(peer.alive()) { "Replacement closed before publication." }
        active.value = peer
        everConnected = true
        replacements.value++
        status.value = BackendConnectionState.Connected
    }

    fun detach(peer: FixturePeer) {
        if (active.value === peer) {
            active.value = null
            status.value = BackendConnectionState.Reconnecting
        }
    }

    suspend fun dropTransport() {
        val peer = active.value ?: return
        detach(peer)
        peer.close()
    }

    fun observe(client: RpcClient, alive: () -> Boolean, close: suspend () -> Unit): FixturePeer {
        lateinit var peer: FixturePeer
        val observed = object : RpcClient by client {
            override suspend fun <T> call(call: RpcCall): T = observeCall(peer, call) { client.call(call) }
        }
        return FixturePeer(observed, alive, close).also { peer = it }
    }

    override suspend fun <T> call(call: RpcCall): T {
        currentCoroutineContext().ensureActive()
        val peer = active.value ?: throw BackendConnectionLost("Disconnected; operation was not submitted.")
        if (!peer.alive()) throw BackendConnectionLost("Disconnected; operation was not submitted.")
        return try { peer.client.call(call) }
        catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!peer.alive()) throw BackendConnectionLost("Fixture transport closed; outcome may be unknown.", failure)
            throw failure
        }
    }

    private suspend fun <T> observeCall(peer: FixturePeer, call: RpcCall, invoke: suspend () -> T): T {
        val runtime = call.descriptor.fqName == "io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc"
        if (runtime) when (call.callableName) {
            "appendUserMessage" -> attempts.value = attempts.value.copy(appends = attempts.value.appends + 1)
            "resume" -> attempts.value = attempts.value.copy(resumes = attempts.value.resumes + 1)
        }
        val loseReply = discardNextAppendReply && runtime && call.callableName == "appendUserMessage"
        if (loseReply) discardNextAppendReply = false
        val result = invoke()
        if (loseReply) {
            // The backend accepted the real write; discard only its delivery to the caller.
            discarded.value++
            detach(peer)
            peer.close()
            error("Fixture discarded an accepted append reply; no automatic replay.")
        }
        return result
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
