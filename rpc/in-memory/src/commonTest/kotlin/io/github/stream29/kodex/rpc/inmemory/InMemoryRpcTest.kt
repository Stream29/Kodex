package io.github.stream29.kodex.rpc.inmemory

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.client.KrpcClient
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.rpc.withService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Rpc
internal interface EchoProbe {
    suspend fun echo(value: String): String
    suspend fun fail()
    suspend fun waitUntilCancelled()
    fun values(): Flow<Int>
    fun heldValues(): Flow<Int>
}

@Rpc
internal interface LengthProbe {
    suspend fun length(value: String): Int
}

internal class EchoProbeImpl : EchoProbe {
    val callStarted = CompletableDeferred<Unit>()
    val callReleased = CompletableDeferred<Unit>()
    val flowStarted = CompletableDeferred<Unit>()
    val flowReleased = CompletableDeferred<Unit>()

    override suspend fun echo(value: String): String = value
    override suspend fun fail(): Nothing = error("ordinary remote failure")

    override suspend fun waitUntilCancelled() {
        try {
            callStarted.complete(Unit)
            awaitCancellation()
        } finally {
            callReleased.complete(Unit)
        }
    }

    override fun values(): Flow<Int> = flowOf(1, 2, 3)
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

private suspend fun checked(block: suspend CoroutineScope.() -> Unit) {
    withTimeout(15.seconds, block)
}

private fun assertFailurePreserved(expected: Throwable, actual: Throwable) {
    assertEquals(expected::class, actual::class)
    assertEquals(expected.message, actual.message)
    // JVM stack-trace recovery may copy the exception, keeping the original as its cause.
    assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
}

val inMemoryRpcTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("two services share a JSON connection without dropping concurrent calls") {
        checked {
            val backend = EchoProbeImpl()
            val result = withInMemoryRpc(
                registerServices = {
                    registerService(EchoProbe::class) { backend }
                    registerService(LengthProbe::class) {
                        object : LengthProbe {
                            override suspend fun length(value: String): Int = value.length
                        }
                    }
                },
            ) { client ->
                val echo = client.withService<EchoProbe>()
                val length = client.withService<LengthProbe>()
                val inputs = List(64) { "你好\n\"RPC-$it\"" }
                assertEquals(inputs, inputs.map { input -> async { echo.echo(input) } }.awaitAll())
                assertEquals(inputs.map { it.length }, inputs.map { input ->
                    async { length.length(input) }
                }.awaitAll())
                assertEquals(listOf(1, 2, 3), echo.values().toList())
                42
            }
            assertEquals(42, result)
        }
    }

    test("unused connection closes without triggering the lazy client completion monitor") {
        checked {
            lateinit var server: KrpcServer
            val parent = currentCoroutineContext().job
            val previousChildren = parent.children.toList()
            assertEquals("unused", withInMemoryRpc(
                registerServices = { server = this as KrpcServer },
            ) { "unused" })
            server.awaitCompletion()
            assertEquals(previousChildren, parent.children.toList())
        }
    }

    test("registration failure releases the partially started server and keeps the original error") {
        checked {
            lateinit var server: KrpcServer
            val failure = IllegalArgumentException("registration failed")
            var blockCalled = false
            val actual = assertFailsWith<IllegalArgumentException> {
                withInMemoryRpc(
                    registerServices = {
                        server = this as KrpcServer
                        throw failure
                    },
                ) { blockCalled = true }
            }
            assertFailurePreserved(failure, actual)
            assertFalse(blockCalled)
            server.awaitCompletion()
        }
    }

    test("block failure after initialization closes endpoints and preserves the original failure") {
        checked {
            lateinit var server: KrpcServer
            lateinit var client: KrpcClient
            val failure = IllegalStateException("frontend failed")
            val actual = assertFailsWith<IllegalStateException> {
                withInMemoryRpc(
                    registerServices = {
                        server = this as KrpcServer
                        registerService(EchoProbe::class) { EchoProbeImpl() }
                    },
                ) {
                    client = it as KrpcClient
                    assertEquals("initialized", it.withService<EchoProbe>().echo("initialized"))
                    throw failure
                }
            }
            assertFailurePreserved(failure, actual)
            server.awaitCompletion()
            client.awaitCompletion()
        }
    }

    test("a frontend child cleanup error is suppressed rather than replacing the block failure") {
        checked {
            val primary = IllegalStateException("primary")
            val cleanup = IllegalArgumentException("child cleanup")
            val actual = assertFailsWith<IllegalStateException> {
                withInMemoryRpc(registerServices = {}) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            throw cleanup
                        }
                    }
                    throw primary
                }
            }
            assertFailurePreserved(primary, actual)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { cause ->
                cause.suppressedExceptions.any { it === cleanup }
            })
        }
    }

    test("outer cancellation releases an active server operation before the owner completes") {
        checked {
            // Exercise sibling cancellation ordering on the multithreaded Native dispatcher.
            repeat(32) {
                val backend = EchoProbeImpl()
                val finished = CompletableDeferred<Unit>()
                val owner = launch {
                    try {
                        withInMemoryRpc(
                            registerServices = { registerService(EchoProbe::class) { backend } },
                        ) { client ->
                            client.withService<EchoProbe>().waitUntilCancelled()
                        }
                    } finally {
                        finished.complete(Unit)
                    }
                }
                backend.callStarted.await()
                owner.cancelAndJoin()
                assertTrue(backend.callReleased.isCompleted)
                assertTrue(finished.isCompleted)
            }
        }
    }

    test("explicit block cancellation remains cancellation and releases its connection") {
        checked {
            val cancellation = CancellationException("leave frontend")
            val actual = assertFailsWith<CancellationException> {
                withInMemoryRpc(registerServices = {}) { throw cancellation }
            }
            assertFailurePreserved(cancellation, actual)
        }
    }

    test("cancelling one call leaves another service and later calls available") {
        checked {
            val backend = EchoProbeImpl()
            withInMemoryRpc(
                registerServices = {
                    registerService(EchoProbe::class) { backend }
                    registerService(LengthProbe::class) {
                        object : LengthProbe {
                            override suspend fun length(value: String): Int = value.length
                        }
                    }
                },
            ) { client ->
                val rpc = client.withService<EchoProbe>()
                val call = launch { rpc.waitUntilCancelled() }
                backend.callStarted.await()
                call.cancelAndJoin()
                backend.callReleased.await()
                assertEquals(2, client.withService<LengthProbe>().length("ok"))
                assertEquals("still connected", rpc.echo("still connected"))
            }
        }
    }

    test("cancelling a buffered subscription releases upstream without closing the connection") {
        checked {
            val backend = EchoProbeImpl()
            withInMemoryRpc(
                registerServices = { registerService(EchoProbe::class) { backend } },
            ) { client ->
                val rpc = client.withService<EchoProbe>()
                val received = CompletableDeferred<Unit>()
                val collection = launch {
                    rpc.heldValues().buffer(0).collect { received.complete(Unit) }
                }
                received.await()
                collection.cancelAndJoin()
                backend.flowReleased.await()
                assertEquals("usable", rpc.echo("usable"))
            }
        }
    }

    test("ordinary remote failure does not terminate the shared connection") {
        checked {
            withInMemoryRpc(
                registerServices = { registerService(EchoProbe::class) { EchoProbeImpl() } },
            ) { client ->
                val rpc = client.withService<EchoProbe>()
                val failure = assertFailsWith<Throwable> { rpc.fail() }
                assertFalse(failure is CancellationException)
                assertEquals("ordinary remote failure", failure.message)
                assertEquals("usable", rpc.echo("usable"))
            }
        }
    }

    test("block children finish before a normal connection shutdown") {
        checked {
            val childFinished = CompletableDeferred<Unit>()
            withInMemoryRpc(
                registerServices = { registerService(EchoProbe::class) { EchoProbeImpl() } },
            ) { client ->
                launch {
                    assertEquals("child", client.withService<EchoProbe>().echo("child"))
                    childFinished.complete(Unit)
                }
            }
            assertTrue(childFinished.isCompleted)
        }
    }

    test("server termination before the first call ends the use scope") {
        checked {
            lateinit var server: KrpcServer
            val frontendReleased = CompletableDeferred<Unit>()
            assertFailsWith<IllegalStateException> {
                withInMemoryRpc(registerServices = { server = this as KrpcServer }) {
                    try {
                        server.close()
                        awaitCancellation()
                    } finally {
                        frontendReleased.complete(Unit)
                    }
                }
            }
            assertTrue(frontendReleased.isCompleted)
            server.awaitCompletion()
        }
    }

    for (side in listOf("client", "server")) {
        test("$side termination after initialization releases frontend and server collectors") {
            checked {
                val backend = EchoProbeImpl()
                lateinit var server: KrpcServer
                lateinit var client: KrpcClient
                val frontendReleased = CompletableDeferred<Unit>()
                assertFailsWith<IllegalStateException> {
                    withInMemoryRpc(
                        registerServices = {
                            server = this as KrpcServer
                            registerService(EchoProbe::class) { backend }
                        },
                    ) {
                        client = it as KrpcClient
                        val rpc = it.withService<EchoProbe>()
                        launch { rpc.heldValues().buffer(0).collect() }
                        backend.flowStarted.await()
                        try {
                            if (side == "client") client.close() else server.close()
                            awaitCancellation()
                        } finally {
                            frontendReleased.complete(Unit)
                        }
                    }
                }
                assertTrue(frontendReleased.isCompleted)
                assertTrue(backend.flowReleased.isCompleted)
                server.awaitCompletion()
                client.awaitCompletion()
            }
        }
    }
}
