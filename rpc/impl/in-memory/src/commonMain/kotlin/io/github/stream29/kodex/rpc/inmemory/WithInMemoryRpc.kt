package io.github.stream29.kodex.rpc.inmemory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.RpcClient
import kotlinx.rpc.RpcServer
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlin.time.Duration.Companion.seconds

// Message count, not a bound on encoded bytes or the framework's internal queues.
private const val TRANSPORT_CAPACITY = 16

/**
 * Registers services and uses a single JSON kRPC connection within [block].
 *
 * The client, service proxies and connection-owned resources must not escape this scope.
 * [block] and its children finish before normal connection teardown. Service implementations'
 * external resources remain owned by their caller; this function does not close arbitrary objects.
 *
 * A failed call or cancelled subscription does not close the connection. Unexpected endpoint
 * termination fails this scope, cancelling its users without reconnecting or replaying calls.
 * Registration failure, block failure and outer cancellation all clean up both endpoints.
 * The client is raw kRPC: known-exception restoration and Flow protection belong in its adapters.
 */
public suspend fun <R> withInMemoryRpc(
    registerServices: RpcServer.() -> Unit,
    block: suspend CoroutineScope.(RpcClient) -> R,
): R = coroutineScope {
    val toServer = Channel<String>(TRANSPORT_CAPACITY)
    val toClient = Channel<String>(TRANSPORT_CAPACITY)
    val clientTransport = ChannelRpcTransport(coroutineContext, toClient, toServer)
    val serverTransport = ChannelRpcTransport(coroutineContext, toServer, toClient)
    val monitors = mutableListOf<Job>()
    var failure: Throwable? = null
    try {
        val server = object : KrpcServer(
            rpcServerConfig { serialization { json() } },
            serverTransport,
        ) {}
        // The client initializes lazily, so awaitCompletion before its first call is not a monitor.
        // Watch its transport instead; also watch the server directly, before transport draining.
        monitors += launch(start = CoroutineStart.UNDISPATCHED) {
            server.awaitCompletion()
            ensureActive()
            this@coroutineScope.ensureActive()
            error("In-memory RPC server terminated while the connection was in use.")
        }
        for ((name, transport) in listOf("client" to clientTransport, "server" to serverTransport)) {
            monitors += launch(start = CoroutineStart.UNDISPATCHED) {
                transport.owner.join()
                ensureActive()
                // Parent cancellation reaches sibling Jobs in sequence. The transport can finish
                // before this monitor is cancelled, so also check the connection's own Job.
                this@coroutineScope.ensureActive()
                error("In-memory RPC $name transport terminated while the connection was in use.")
            }
        }
        server.registerServices()
        ensureActive()
        val client = object : InitializedKrpcClient(
            rpcClientConfig { serialization { json() } },
            clientTransport,
        ) {}
        coroutineScope { block(client) }
    } catch (cause: Throwable) {
        failure = cause
        throw cause
    } finally {
        // Disable termination monitors before intentional shutdown. Cancel both sides before
        // awaiting either: a peer can be suspended sending to a full channel.
        monitors.forEach { it.cancel() }
        clientTransport.beginShutdown()
        serverTransport.beginShutdown()
        clientTransport.owner.cancel()
        serverTransport.owner.cancel()
        toServer.cancel()
        toClient.cancel()
        try {
            withContext(NonCancellable) {
                withTimeout(10.seconds) {
                    monitors.forEach { it.join() }
                    clientTransport.owner.join()
                    serverTransport.owner.join()
                }
            }
        } catch (cleanup: Throwable) {
            val original = failure
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        }
    }
}
