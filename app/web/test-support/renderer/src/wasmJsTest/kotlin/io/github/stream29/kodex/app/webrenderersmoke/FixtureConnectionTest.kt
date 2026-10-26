@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.app.webrenderersmoke

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
val fixtureConnectionTest by testSuite {
    test("disconnected command is rejected without submitting") {
        val connection = FixtureConnection()
        assertFailsWith<BackendConnectionLost> { RpcServices(connection).runtime.resume(41) }
        assertFalse(connection.available)
    }
    test("caller cancellation is not reclassified as a lost transport") {
        runTest {
            val connection = FixtureConnection()
            var caught: Throwable? = null
            launch {
                currentCoroutineContext().job.cancel()
                try { RpcServices(connection).runtime.resume(41) }
                catch (failure: Throwable) { caught = failure }
            }.join()
            assertIs<CancellationException>(caught)
            assertEquals(FixtureWriteAttempts(), connection.writeAttempts.value)
        }
    }
    test("accepted append with discarded reply is never replayed or resumed on replacement") {
        val connection = FixtureConnection()
        val first = TestPeer(connection)
        connection.install(first.peer)
        connection.discardNextAppendReply = true
        assertFailsWith<BackendConnectionLost> {
            RpcServices(connection).runtime.appendUserMessage(41, emptyList())
        }
        assertEquals(listOf("appendUserMessage"), first.calls)
        assertFalse(first.alive)
        assertFalse(connection.available)
        val second = TestPeer(connection)
        connection.install(second.peer)
        assertEquals(1, connection.discardedReplies.value)
        assertEquals(2, connection.generation.value)
        assertEquals(emptyList(), second.calls)
        assertEquals(listOf("appendUserMessage"), first.calls)
    }
    test("state subscription resumes only after exact replacement preparation") {
        runTest {
            val connection = FixtureConnection()
            val first = TestPeer(connection, 1)
            connection.install(first.peer)
            val values = mutableListOf<Int>()
            val collecting = async {
                RpcServices(connection).runtime.getLatestIndexFlow(41).take(2).toList(values)
            }
            runCurrent()
            assertEquals(listOf(1), values)
            first.alive = false
            connection.detach(first.peer)
            runCurrent()
            val second = TestPeer(connection, 2)
            var prepared = false
            connection.onReconnection { client, alive ->
                assertSame(second.peer.client, client)
                assertTrue(alive())
                assertFalse(connection.available)
                assertEquals(BackendConnectionState.Reconnecting, connection.state.value)
                prepared = true
            }
            connection.install(second.peer)
            assertTrue(prepared)
            assertEquals(listOf(1, 2), collecting.await())
        }
    }
    test("replacement-bound services are observed at their exact peer rather than only at the routing facade") {
        val connection = FixtureConnection()
        val first = TestPeer(connection)
        connection.install(first.peer)
        connection.detach(first.peer)
        lateinit var bound: RpcClient
        connection.onReconnection { client, _ -> bound = client }
        val second = TestPeer(connection)
        connection.install(second.peer)
        connection.discardNextAppendReply = true
        assertFailsWith<IllegalStateException> { RpcServices(bound).runtime.appendUserMessage(41, emptyList()) }
        assertEquals(listOf("appendUserMessage"), second.calls)
        assertEquals(FixtureWriteAttempts(appends = 1), connection.writeAttempts.value)
        assertEquals(1, connection.discardedReplies.value)
        assertFalse(connection.available)
        assertTrue(first.calls.isEmpty())
    }
    test("live business and replacement preparation failures propagate without publication or retry") {
        val connection = FixtureConnection()
        val first = TestPeer(connection, fail = true)
        connection.install(first.peer)
        val service = RpcServices(connection).runtime
        assertFailsWith<FixtureBusinessFailure> { service.getLatestIndex(41) }
        assertFailsWith<FixtureBusinessFailure> { service.getLatestIndexFlow(41).toList() }
        assertEquals(listOf("getLatestIndex"), first.calls)
        assertEquals(1, first.subscriptions)
        connection.detach(first.peer)
        val second = TestPeer(connection)
        connection.onReconnection { _, _ -> throw FixtureBusinessFailure() }
        assertFailsWith<FixtureBusinessFailure> { connection.install(second.peer) }
        assertFalse(connection.available)
        assertEquals(1, connection.generation.value)
    }
}

private class FixtureBusinessFailure : IllegalStateException("live business failure")

private class TestPeer(connection: FixtureConnection, private val index: Int = 17, private val fail: Boolean = false) {
    var alive = true
    val calls = mutableListOf<String>()
    var subscriptions = 0
    val peer = connection.observe(object : RpcClient {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> call(call: RpcCall): T {
            calls += call.callableName
            if (fail) throw FixtureBusinessFailure()
            return index as T
        }
        @Suppress("UNCHECKED_CAST")
        override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
            subscriptions++
            if (fail) throw FixtureBusinessFailure()
            emit(index as T)
            awaitCancellation()
        }
    }, { alive }, { alive = false })
}
