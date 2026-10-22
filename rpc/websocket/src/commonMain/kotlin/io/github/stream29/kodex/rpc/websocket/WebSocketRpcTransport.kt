@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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

public actual class WebSocketRpcTransport actual constructor(
    private val socket: WebSocketSession,
    context: CoroutineContext,
) : KrpcTransport {
    private val owner = SupervisorJob(context.job)
    private val closed = CompletableDeferred<Unit>()
    @kotlin.concurrent.Volatile
    public actual var alive: Boolean = true
        private set
    actual override val coroutineContext: CoroutineContext = context + owner + CoroutineExceptionHandler { _, _ -> lost() }
    init { socket.coroutineContext.job.invokeOnCompletion { lost() } }
    private fun lost() {
        alive = false
        closed.complete(Unit)
        owner.cancel()
    }
    actual override suspend fun send(message: KrpcTransportMessage) {
        require(message is KrpcTransportMessage.StringMessage)
        try { socket.send(Frame.Text(message.value)) }
        catch (failure: Throwable) { lost(); throw failure }
    }
    actual override suspend fun receive(): KrpcTransportMessage {
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
    public actual suspend fun awaitClosed() { closed.await() }
    public actual suspend fun shutdown() {
        lost()
        try { socket.close() } finally { owner.cancelAndJoin() }
    }
}
