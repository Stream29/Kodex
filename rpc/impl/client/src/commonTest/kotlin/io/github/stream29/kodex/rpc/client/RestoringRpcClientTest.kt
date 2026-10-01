package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val restoringRpcClientTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("generated proxies preserve nullable unary results and ordered streaming values") {
        withProbe { rpc, _ ->
            assertNull(rpc.echo(null))
            assertEquals("你好\n\"RPC\"", rpc.echo("你好\n\"RPC\""))
            assertEquals(listOf(1, 2, 3), rpc.values().toList())
        }
    }

    for (kind in listOf("noMatch", "inactive", "notFound", "cacheNonce")) {
        test("$kind restores from a real unary failure without retrying") {
            withProbe { rpc, backend ->
                assertKnown(kind, assertFailsWith<RemoteException> { rpc.fail(kind) })
                assertEquals(1, backend.attempts.value)
                assertEquals("alive", rpc.echo("alive"))
            }
        }

        test("$kind restores when the server rejects Flow binding") {
            withProbe { rpc, backend ->
                assertKnown(kind, assertFailsWith<RemoteException> {
                    rpc.failingValues(kind, failAtBinding = true).collect()
                })
                assertEquals(1, backend.attempts.value)
                assertEquals("alive", rpc.echo("alive"))
            }
        }

        test("$kind restores after the first element and releases upstream") {
            withProbe { rpc, backend ->
                val values = mutableListOf<Int>()
                assertKnown(kind, assertFailsWith<RemoteException> {
                    rpc.failingValues(kind, failAtBinding = false).collect {
                        values += it
                        backend.firstValueReceived.complete(Unit)
                    }
                })
                backend.flowReleased.await()
                assertEquals(listOf(1), values)
                assertEquals(1, backend.attempts.value)
                assertEquals("alive", rpc.echo("alive"))
            }
        }
    }

    for (message in listOf("ordinary remote failure", "{", """{"type":"unknown.Exception"}""")) {
        test("unknown payload $message remains a non-typed unary and Flow failure") {
            withProbe { rpc, backend ->
                val unary = assertFailsWith<Throwable> { rpc.fail(message) }
                assertFalse(unary is RemoteException)
                assertFalse(unary is CancellationException)
                assertEquals(message, unary.message)
                val streaming = assertFailsWith<Throwable> {
                    rpc.failingValues(message, failAtBinding = false).collect {
                        backend.firstValueReceived.complete(Unit)
                    }
                }
                backend.flowReleased.await()
                assertFalse(streaming is RemoteException)
                assertFalse(streaming is CancellationException)
                assertEquals(message, streaming.message)
                assertEquals(2, backend.attempts.value)
                assertEquals("alive", rpc.echo("alive"))
            }
        }
    }

    test("remote cancellation carrying known JSON is not decoded or retried") {
        withProbe { rpc, backend ->
            assertFailsWith<CancellationException> { rpc.fail("cancel") }
            assertFailsWith<CancellationException> {
                rpc.failingValues("cancel", failAtBinding = false).collect {
                    backend.firstValueReceived.complete(Unit)
                }
            }
            backend.flowReleased.await()
            assertEquals(2, backend.attempts.value)
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("ordinary downstream failure carrying known JSON stays local and releases upstream") {
        withProbe { rpc, backend ->
            val local = IllegalArgumentException(NoMatchException().message)
            val actual = assertFailsWith<IllegalArgumentException> {
                rpc.heldValues().collect { throw local }
            }
            assertEquals(local.message, actual.message)
            // JVM coroutine stack recovery may copy this exception, preserving the original cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === local })
            backend.flowReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("cancelling a collector releases only its upstream subscription") {
        withProbe { rpc, backend ->
            val received = CompletableDeferred<Unit>()
            val collection = launch {
                rpc.heldValues().collect { received.complete(Unit) }
            }
            received.await()
            collection.cancelAndJoin()
            backend.flowReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("take short circuits the server flow and leaves the connection alive") {
        withProbe { rpc, backend ->
            assertEquals(listOf(1), rpc.heldValues().take(1).toList())
            backend.flowReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("cancelling a unary caller releases its request without closing the connection") {
        withProbe { rpc, backend ->
            val operation = launch { rpc.waitUntilCancelled() }
            backend.callStarted.await()
            operation.cancelAndJoin()
            backend.callReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("obtaining a Flow does not create a server subscription") {
        withProbe { rpc, backend ->
            val values = rpc.heldValues()
            // This call also confirms the connection has processed traffic after Flow creation.
            assertEquals("alive", rpc.echo("alive"))
            assertFalse(backend.flowStarted.isCompleted)
            assertEquals(listOf(1), values.take(1).toList())
            backend.flowReleased.await()
        }
    }
}
