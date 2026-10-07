package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.rpc.withService
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Test-only service: it does not implement any Session, owner, or business operation. */
@Rpc
internal interface RpcBoundaryProbe {
    suspend fun echo(value: String): String
    suspend fun fail(kind: String)
    suspend fun waitForCancellation()
    fun values(): Flow<Int>
    fun failingValues(kind: String, failAtBinding: Boolean): Flow<Int>
    fun heldValues(): Flow<Int>
}

internal class RpcBoundaryProbeImpl : RpcBoundaryProbe {
    val firstValueReceived = CompletableDeferred<Unit>()
    val flowStarted = CompletableDeferred<Unit>()
    val flowReleased = CompletableDeferred<Unit>()
    val callStarted = CompletableDeferred<Unit>()
    val callReleased = CompletableDeferred<Unit>()

    override suspend fun echo(value: String): String = value

    override suspend fun fail(kind: String) {
        throw when (kind) {
            "noMatch" -> NoMatchException()
            "inactive" -> SessionNotActive()
            "notFound" -> SessionNotFound()
            "cacheNonce" -> CacheNonceMismatch()
            "cancellation" -> CancellationException(NoMatchException().message)
            else -> Throwable(kind)
        }
    }

    override suspend fun waitForCancellation() {
        try {
            callStarted.complete(Unit)
            awaitCancellation()
        } finally {
            callReleased.complete(Unit)
        }
    }

    override fun values(): Flow<Int> = flowOf(1, 2, 3)

    override fun failingValues(kind: String, failAtBinding: Boolean): Flow<Int> {
        if (failAtBinding) throw when (kind) {
            "noMatch" -> NoMatchException()
            "inactive" -> SessionNotActive()
            "notFound" -> SessionNotFound()
            "cacheNonce" -> CacheNonceMismatch()
            else -> error("Unexpected test failure kind: $kind")
        }
        return flow {
            try {
                emit(1)
                // Do not let a terminal error overtake the first value in the transport buffer.
                firstValueReceived.await()
                fail(kind)
            } finally {
                flowReleased.complete(Unit)
            }
        }
    }

    override fun heldValues(): Flow<Int> = flow {
        try {
            flowStarted.complete(Unit)
            emit(1)
            awaitCancellation()
        } finally {
            flowReleased.complete(Unit)
        }
    }
}

private class ChannelRpcTransport(
    context: CoroutineContext,
    private val incoming: Channel<KrpcTransportMessage>,
    private val outgoing: Channel<KrpcTransportMessage>,
) : KrpcTransport {
    val owner = Job(context.job)
    override val coroutineContext: CoroutineContext = context + owner

    override suspend fun send(message: KrpcTransportMessage) {
        // JSON-encoded protocol messages cross the boundary, not service objects or exceptions.
        check(message is KrpcTransportMessage.StringMessage)
        outgoing.send(KrpcTransportMessage.StringMessage(message.value))
    }

    override suspend fun receive(): KrpcTransportMessage = incoming.receive()
}

internal suspend fun <R> withInMemoryRpc(
    block: suspend CoroutineScope.(RpcBoundaryProbe, RpcBoundaryProbeImpl) -> R,
): R = coroutineScope {
    val toServer = Channel<KrpcTransportMessage>(16)
    val toClient = Channel<KrpcTransportMessage>(16)
    val clientTransport = ChannelRpcTransport(coroutineContext, toClient, toServer)
    val serverTransport = ChannelRpcTransport(coroutineContext, toServer, toClient)
    var failure: Throwable? = null
    try {
        val backend = RpcBoundaryProbeImpl()
        val server = object : KrpcServer(
            rpcServerConfig { serialization { json() } },
            serverTransport,
        ) {}
        server.registerService(RpcBoundaryProbe::class) { backend }
        val client = object : InitializedKrpcClient(
            rpcClientConfig { serialization { json() } },
            clientTransport,
        ) {}
        withTimeout(15.seconds) {
            block(this@coroutineScope, client.withService<RpcBoundaryProbe>(), backend)
        }
    } catch (cause: Throwable) {
        failure = cause
        throw cause
    } finally {
        // Each transport owns its endpoint; cancel both before awaiting either one's children.
        clientTransport.owner.cancel()
        serverTransport.owner.cancel()
        toServer.cancel()
        toClient.cancel()
        try {
            withContext(NonCancellable) {
                withTimeout(5.seconds) {
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
