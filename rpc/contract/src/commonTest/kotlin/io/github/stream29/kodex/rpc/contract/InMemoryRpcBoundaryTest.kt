package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.RemoteException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.restoreRemoteException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun <T> Flow<T>.restoreFailures(): Flow<T> = catch { cause ->
    restoreRemoteException { throw cause }
}

private fun assertKnown(kind: String, failure: Throwable) {
    when (kind) {
        "noMatch" -> assertIs<NoMatchException>(failure)
        "inactive" -> assertIs<SessionNotActive>(failure)
        "notFound" -> assertIs<SessionNotFound>(failure)
        "cacheNonce" -> assertIs<CacheNonceMismatch>(failure)
        else -> error("Unexpected test failure kind: $kind")
    }
}

val inMemoryRpcBoundaryTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("JSON transport completes unary and finite streaming calls") {
        withInMemoryRpc { rpc, _ ->
            assertEquals("你好\n\"RPC\"", rpc.echo("你好\n\"RPC\""))
            assertEquals(listOf(1, 2, 3), rpc.values().toList())
        }
    }

    for (kind in listOf("noMatch", "inactive", "cacheNonce", "notFound")) {
        test("$kind restores from a real unary failure") {
            withInMemoryRpc { rpc, _ ->
                assertKnown(kind, assertFailsWith<RemoteException> {
                    restoreRemoteException { rpc.fail(kind) }
                })
                assertEquals("still connected", rpc.echo("still connected"))
            }
        }

        test("$kind restores when the server rejects flow binding") {
            withInMemoryRpc { rpc, _ ->
                assertKnown(kind, assertFailsWith<RemoteException> {
                    rpc.failingValues(kind, failAtBinding = true).restoreFailures().collect()
                })
                assertEquals("still connected", rpc.echo("still connected"))
            }
        }

        test("$kind restores after a delivered flow value and releases upstream") {
            withInMemoryRpc { rpc, backend ->
                val values = mutableListOf<Int>()
                assertKnown(kind, assertFailsWith<RemoteException> {
                    rpc.failingValues(kind, failAtBinding = false).restoreFailures().collect {
                        values += it
                        backend.firstValueReceived.complete(Unit)
                    }
                })
                assertEquals(listOf(1), values)
                backend.flowReleased.await()
                assertEquals(listOf(1, 2, 3), rpc.values().toList())
            }
        }
    }

    test("unknown and malformed messages remain unknown in unary and streaming failures") {
        withInMemoryRpc { rpc, backend ->
            for (message in listOf("ordinary failure", "{", """{"type":"unknown.Exception"}""")) {
                val unary = assertFailsWith<Throwable> {
                    restoreRemoteException { rpc.fail(message) }
                }
                assertFalse(unary is RemoteException)
                assertFalse(unary is CancellationException)
                assertEquals(message, unary.message)
                val streaming = assertFailsWith<Throwable> {
                    rpc.failingValues(message, failAtBinding = false).restoreFailures().collect {
                        backend.firstValueReceived.complete(Unit)
                    }
                }
                assertFalse(streaming is RemoteException)
                assertFalse(streaming is CancellationException)
                assertEquals(message, streaming.message)
            }
        }
    }

    test("remote cancellation with a valid JSON message is not a business exception") {
        withInMemoryRpc { rpc, backend ->
            assertFailsWith<CancellationException> {
                restoreRemoteException { rpc.fail("cancellation") }
            }
            assertFailsWith<CancellationException> {
                rpc.failingValues("cancellation", failAtBinding = false).restoreFailures().collect {
                    backend.firstValueReceived.complete(Unit)
                }
            }
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("cancelling a collector releases only that server subscription") {
        withInMemoryRpc { rpc, backend ->
            val otherCall = async { rpc.waitForCancellation() }
            backend.callStarted.await()
            val collecting = launch { rpc.heldValues().restoreFailures().collect() }
            backend.flowStarted.await()
            collecting.cancelAndJoin()
            backend.flowReleased.await()
            assertTrue(collecting.isCancelled)
            assertFalse(backend.callReleased.isCompleted)
            assertEquals("alive", rpc.echo("alive"))
            assertEquals(listOf(1, 2, 3), rpc.values().toList())
            otherCall.cancelAndJoin()
            backend.callReleased.await()
        }
    }

    test("cancelling a unary caller reaches the server handler without closing the connection") {
        withInMemoryRpc { rpc, backend ->
            val calling = async { restoreRemoteException { rpc.waitForCancellation() } }
            backend.callStarted.await()
            calling.cancel(CancellationException(NoMatchException().message))
            assertFailsWith<CancellationException> { calling.await() }
            backend.callReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("direct collector failure stays local but 0.10.3 leaves server cleanup to endpoint shutdown") {
        var implementation: RpcBoundaryProbeImpl? = null
        withInMemoryRpc { rpc, backend ->
            implementation = backend
            val local = Throwable(NoMatchException().message)
            var recoveryCalls = 0
            val failure = assertFailsWith<Throwable> {
                rpc.heldValues().catch { cause ->
                    recoveryCalls++
                    restoreRemoteException { throw cause }
                }.collect { throw local }
            }
            // JVM coroutine stack-trace recovery may copy a Throwable across suspension.
            assertEquals(0, recoveryCalls)
            assertFalse(failure is RemoteException)
            assertFalse(failure is CancellationException)
            assertEquals(local.message, failure.message)
            assertEquals("alive", rpc.echo("alive"))
            // Characterize the framework gap, rather than claim ordinary failure is cancellation.
            assertFalse(backend.flowReleased.isCompleted)
        }
        assertTrue(requireNotNull(implementation).flowReleased.isCompleted)
    }

    test("a rendezvous Flow boundary preserves local failure and cancels only its remote upstream") {
        withInMemoryRpc { rpc, backend ->
            val local = Throwable(NoMatchException().message)
            var recoveryCalls = 0
            val failure = assertFailsWith<Throwable> {
                rpc.heldValues().buffer(0).catch { cause ->
                    recoveryCalls++
                    restoreRemoteException { throw cause }
                }.collect { throw local }
            }
            assertEquals(0, recoveryCalls)
            assertFalse(failure is RemoteException)
            assertFalse(failure is CancellationException)
            assertEquals(local.message, failure.message)
            backend.flowReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("a rendezvous Flow boundary also preserves remote failures and cancellation") {
        for (kind in listOf("noMatch", "inactive", "cacheNonce", "notFound", "cancellation")) {
            withInMemoryRpc { rpc, backend ->
                val failure = assertFailsWith<Throwable> {
                    rpc.failingValues(kind, failAtBinding = false).buffer(0).restoreFailures().collect {
                        backend.firstValueReceived.complete(Unit)
                    }
                }
                if (kind == "cancellation") assertIs<CancellationException>(failure)
                else assertKnown(kind, failure)
                backend.flowReleased.await()
                assertEquals("alive", rpc.echo("alive"))
            }
        }
    }

    test("fixture owner shutdown releases a still-active server collector before returning") {
        var backend: RpcBoundaryProbeImpl? = null
        withInMemoryRpc { rpc, implementation ->
            backend = implementation
            launch { rpc.heldValues().collect() }
            implementation.flowStarted.await()
        }
        assertTrue(requireNotNull(backend).flowReleased.isCompleted)
    }
}
