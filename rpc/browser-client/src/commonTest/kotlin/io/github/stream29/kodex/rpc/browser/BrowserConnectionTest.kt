@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.LocalBrowserEndpoint
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.cli.rpc.RpcServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

private val endpoint = LocalBrowserEndpoint("instance", 12345, "a".repeat(64), BackendBusinessProtocol)
private val unusedClient = object : RpcClient {
    override suspend fun <T> call(call: RpcCall): T = error("Unexpected call")
    override fun <T> callServerStreaming(call: RpcCall) = emptyFlow<T>()
}

internal val browserConnectionTest by testSuite {
    test("validate descriptor before networking") {
        validateBrowserEndpoint(endpoint)
        for (invalid in listOf(
            LocalBrowserEndpoint("instance", 0, "a".repeat(64), BackendBusinessProtocol),
            LocalBrowserEndpoint("instance", 123, "short", BackendBusinessProtocol),
            LocalBrowserEndpoint("instance", 123, "a".repeat(64), "old"),
        )) assertFailsWith<IllegalArgumentException> { validateBrowserEndpoint(invalid) }
    }
    test("production heartbeat schema and TTL") {
        fun status(ttl: Long, instance: String = "instance", phase: String = "ready") = buildJsonObject {
            put("instance", instance); put("phase", phase)
            put("business_protocol", BackendBusinessProtocol); put("idle_ttl_seconds", ttl)
        }
        assertEquals(20_000L, heartbeatInterval(status(-1), endpoint))
        assertEquals(20_000L, heartbeatInterval(status(300), endpoint))
        assertEquals(333L, heartbeatInterval(status(1), endpoint))
        assertFails { heartbeatInterval(status(0), endpoint) }
        assertFails { heartbeatInterval(status(300, "other"), endpoint) }
        assertFails { heartbeatInterval(status(300, phase = "stopping"), endpoint) }
    }
    test("recovery precedes publication and rejects dead replacement") {
        val connection = BrowserConnection()
        val first = BrowserPeer(unusedClient) { true }
        connection.install(first)
        connection.detach(first)
        var recovered = false
        connection.onReconnection { _, alive ->
            assertFalse(connection.available)
            assertTrue(alive())
            recovered = true
        }
        val second = BrowserPeer(unusedClient) { true }
        connection.install(second)
        assertTrue(recovered)
        connection.detach(first)
        assertTrue(connection.available)
        connection.detach(second)
        connection.onReconnection { _, _ -> }
        assertFailsWith<IllegalStateException> { connection.install(BrowserPeer(unusedClient) { false }) }
        assertFalse(connection.available)
    }
    test("live recovery failure is not swallowed or published") {
        val connection = BrowserConnection()
        val first = BrowserPeer(unusedClient) { true }
        connection.install(first); connection.detach(first)
        val failure = IllegalArgumentException("business recovery")
        connection.onReconnection { _, _ -> throw failure }
        assertSame(failure, assertFailsWith<IllegalArgumentException> { connection.install(BrowserPeer(unusedClient) { true }) })
        assertFalse(connection.available)
    }
    test("rejection remains terminal after stale detach") {
        val connection = BrowserConnection()
        val peer = BrowserPeer(unusedClient) { true }
        connection.install(peer); connection.reject(); connection.detach(peer)
        assertEquals(BackendConnectionState.Failed, connection.state.value)
        assertFalse(connection.available)
        assertNotNull(connection.failureReason.value)
        assertFailsWith<IllegalStateException> { connection.install(BrowserPeer(unusedClient) { true }) }
    }
    test("renewal rejection during recovery cannot publish a replacement") {
        val connection = BrowserConnection()
        val first = BrowserPeer(unusedClient) { true }
        connection.install(first); connection.detach(first)
        connection.onReconnection { _, _ -> connection.reject() }
        assertFailsWith<IllegalStateException> { connection.install(BrowserPeer(unusedClient) { true }) }
        assertFalse(connection.available)
        assertEquals(BackendConnectionState.Failed, connection.state.value)
    }
    test("offline command is never submitted") {
        assertFailsWith<BackendConnectionLost> { RpcServices(BrowserConnection()).runtime.resume(41) }
    }
    test("unknown write result is not replayed on replacement") {
        val connection = BrowserConnection()
        var alive = true
        var writes = 0
        val client = object : RpcClient by unusedClient {
            override suspend fun <T> call(call: RpcCall): T {
                writes++
                alive = false
                error("Accepted write lost its reply")
            }
        }
        val first = BrowserPeer(client) { alive }
        connection.install(first)
        assertFailsWith<BackendConnectionLost> { RpcServices(connection).runtime.resume(41) }
        connection.detach(first)
        connection.install(BrowserPeer(unusedClient) { true })
        assertEquals(1, writes)
    }
    test("caller cancellation is not reported as transport loss") {
        runTest {
            val connection = BrowserConnection()
            var failure: Throwable? = null
            launch {
                currentCoroutineContext().job.cancel()
                try { RpcServices(connection).runtime.resume(41) }
                catch (caught: Throwable) { failure = caught }
            }.join()
            assertIs<CancellationException>(failure)
        }
    }
    test("live business failures retain their cause without reconnect or retry") {
        val connection = BrowserConnection()
        val original = IllegalArgumentException("business failure")
        var calls = 0
        val client = object : RpcClient by unusedClient {
            override suspend fun <T> call(call: RpcCall): T { calls++; throw original }
            override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow { throw original }
        }
        connection.install(BrowserPeer(client) { true })
        assertSame(original, assertFailsWith<IllegalArgumentException> { RpcServices(connection).runtime.getLatestIndex(41) })
        assertSame(original, assertFailsWith<IllegalArgumentException> { RpcServices(connection).runtime.getLatestIndexFlow(41).toList() })
        assertEquals(1, calls)
        assertTrue(connection.available)
    }
    test("streams resume only after exact replacement recovery") {
        runTest {
            val connection = BrowserConnection()
            fun peer(index: Int) = BrowserPeer(object : RpcClient by unusedClient {
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
                    emit(index as T); awaitCancellation()
                }
            }) { true }
            val first = peer(1)
            connection.install(first)
            val values = mutableListOf<Int>()
            val observing = async { RpcServices(connection).runtime.getLatestIndexFlow(41).take(2).toList(values) }
            runCurrent()
            assertEquals(listOf(1), values)
            connection.detach(first)
            runCurrent()
            var recovered = false
            connection.onReconnection { _, _ ->
                assertFalse(connection.available)
                assertEquals(listOf(1), values)
                recovered = true
            }
            connection.install(peer(2))
            observing.await()
            assertTrue(recovered)
            assertEquals(listOf(1, 2), values)
        }
    }
}
