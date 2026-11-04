package io.github.stream29.kodex.rpc.websocket

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val webSocketRpcTransportTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    for (failure in listOf("send-stalls", "flush-stalls", "send-fails", "flush-fails", "send-cancelled", "flush-cancelled", "healthy")) {
        test("shutdown is bounded and repeatable when $failure") {
            runTest {
                val socket = ShutdownSocket(failure)
                val transport = WebSocketRpcTransport(socket, coroutineContext)
                transport.shutdown()
                transport.shutdown()
                transport.awaitClosed()
                assertFalse(transport.alive)
                assertTrue(transport.coroutineContext.job.isCompleted)
                if (failure != "healthy") assertTrue(socket.coroutineContext.job.isCancelled)
                socket.coroutineContext.job.cancel()
            }
        }
    }
}

private class ShutdownSocket(private val failure: String) : WebSocketSession {
    override val coroutineContext: CoroutineContext = Job()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming = Channel<Frame>()
    override val outgoing = Channel<Frame>(Channel.UNLIMITED)
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override suspend fun send(frame: Frame) {
        if (failure == "send-stalls") awaitCancellation()
        if (failure == "send-cancelled") throw CancellationException("send cancelled")
        if (failure == "send-fails") error("send failed")
    }
    override suspend fun flush() {
        if (failure == "flush-stalls") awaitCancellation()
        if (failure == "flush-cancelled") throw CancellationException("flush cancelled")
        if (failure == "flush-fails") error("flush failed")
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun terminate() { coroutineContext.job.cancel() }
}
