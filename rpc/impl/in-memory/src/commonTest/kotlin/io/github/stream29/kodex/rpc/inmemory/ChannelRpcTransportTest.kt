package io.github.stream29.kodex.rpc.inmemory

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val channelRpcTransportTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("shutdown boundary ignores only late closed send replies and preserves live error reporting") {
        coroutineScope {
            val reported = mutableListOf<Throwable>()
            val incoming = Channel<String>(1)
            val outgoing = Channel<String>(1)
            val transport = ChannelRpcTransport(
                coroutineContext + CoroutineExceptionHandler { _, failure -> reported += failure },
                incoming, outgoing,
            )
            try {
                val errors = checkNotNull(transport.coroutineContext[CoroutineExceptionHandler])
                val live = ClosedSendChannelException("live connection send failure")
                errors.handleException(transport.coroutineContext, live)
                assertEquals(listOf<Throwable>(live), reported)
                transport.beginShutdown()
                errors.handleException(transport.coroutineContext, ClosedSendChannelException("late cancellation reply"))
                val unrelated = IllegalStateException("unrelated cleanup bug")
                errors.handleException(transport.coroutineContext, unrelated)
                assertEquals(listOf<Throwable>(live, unrelated), reported)
            } finally {
                transport.owner.cancelAndJoin()
                incoming.cancel()
                outgoing.cancel()
            }
        }
    }
    test("full channel suspends sends and preserves FIFO messages") {
        withTimeout(15.seconds) {
            coroutineScope {
                val incoming = Channel<String>(1)
                val outgoing = Channel<String>(1)
                val transport = ChannelRpcTransport(coroutineContext, incoming, outgoing)
                try {
                    transport.send(KrpcTransportMessage.StringMessage("first"))
                    val send = async(start = CoroutineStart.UNDISPATCHED) {
                        transport.send(KrpcTransportMessage.StringMessage("second"))
                    }
                    assertFalse(send.isCompleted)
                    assertEquals("first", outgoing.receive())
                    send.await()
                    assertEquals("second", outgoing.receive())
                    incoming.send("encoded response")
                    assertEquals(
                        "encoded response",
                        assertIs<KrpcTransportMessage.StringMessage>(transport.receive()).value,
                    )
                } finally {
                    transport.owner.cancel()
                    incoming.cancel()
                    outgoing.cancel()
                    transport.owner.join()
                }
            }
        }
    }

    test("JSON transport rejects binary messages without enqueuing them") {
        coroutineScope {
            val incoming = Channel<String>(1)
            val outgoing = Channel<String>(1)
            val transport = ChannelRpcTransport(coroutineContext, incoming, outgoing)
            try {
                assertFailsWith<IllegalStateException> {
                    transport.send(KrpcTransportMessage.BinaryMessage(byteArrayOf(1)))
                }
                assertTrue(outgoing.tryReceive().isFailure)
            } finally {
                transport.owner.cancel()
                incoming.cancel()
                outgoing.cancel()
                transport.owner.join()
            }
        }
    }

    test("owner cancellation releases simultaneous blocked send and receive") {
        withTimeout(15.seconds) {
            coroutineScope {
                val incoming = Channel<String>(0)
                val outgoing = Channel<String>(0)
                val transport = ChannelRpcTransport(coroutineContext, incoming, outgoing)
                try {
                    val send = transport.launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.send(KrpcTransportMessage.StringMessage("blocked"))
                        awaitCancellation()
                    }
                    val receive = transport.launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.receive()
                        awaitCancellation()
                    }
                    assertFalse(send.isCompleted)
                    assertFalse(receive.isCompleted)
                    transport.owner.cancelAndJoin()
                    assertTrue(send.isCompleted)
                    assertTrue(receive.isCompleted)
                } finally {
                    transport.owner.cancel()
                    incoming.cancel()
                    outgoing.cancel()
                    transport.owner.join()
                }
            }
        }
    }
}
