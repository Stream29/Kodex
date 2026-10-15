package io.github.stream29.kodex.rpc.websocket

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlin.coroutines.CoroutineContext

public class WebSocketRpcTransport(
    private val socket: WebSocketSession,
    context: CoroutineContext,
) : KrpcTransport {
    private val owner = SupervisorJob(context.job)
    private val closed = CompletableDeferred<Unit>()
    @kotlin.concurrent.Volatile
    public var alive: Boolean = true
        private set
    override val coroutineContext: CoroutineContext = context + owner + CoroutineExceptionHandler { _, _ -> lost() }
    init { socket.coroutineContext.job.invokeOnCompletion { lost() } }
    private fun lost() {
        alive = false
        closed.complete(Unit)
        owner.cancel()
    }
    override suspend fun send(message: KrpcTransportMessage) {
        require(message is KrpcTransportMessage.StringMessage)
        try { socket.send(Frame.Text(message.value)) }
        catch (failure: Throwable) { lost(); throw failure }
    }
    override suspend fun receive(): KrpcTransportMessage {
        try {
            while (true) {
                when (val frame = socket.incoming.receive()) {
                    is Frame.Text -> return KrpcTransportMessage.StringMessage(frame.readText())
                    is Frame.Close -> throw CancellationException("Backend connection closed.")
                    is Frame.Binary -> error("Binary RPC messages are not accepted.")
                    else -> Unit
                }
            }
        } catch (failure: Throwable) { lost(); throw failure }
    }
    public suspend fun awaitClosed() { closed.await() }
    public suspend fun shutdown() {
        lost()
        try { socket.close() } finally { owner.cancelAndJoin() }
    }
}
