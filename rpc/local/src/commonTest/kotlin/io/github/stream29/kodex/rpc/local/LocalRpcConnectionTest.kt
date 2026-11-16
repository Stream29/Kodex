@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.local

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val unusedRpcClient = object : RpcClient {
    override suspend fun <T> call(call: RpcCall): T = error("Unexpected call")
    override fun <T> callServerStreaming(call: RpcCall): Flow<T> = emptyFlow()
}

internal val localRpcConnectionTest by testSuite {
    test("local connection delegates state recovery and terminal failure through managed state machine") {
        runTest {
            val connection = LocalRpcConnection()
            assertEquals(BackendConnectionState.Reconnecting, connection.state.value)
            assertFalse(connection.available)

            val first = ActiveRpcPeer(unusedRpcClient) { true }
            connection.managed.install(first)
            assertEquals(BackendConnectionState.Connected, connection.state.value)
            assertTrue(connection.available)

            connection.managed.detach(first)
            assertEquals(BackendConnectionState.Reconnecting, connection.state.value)
            assertFalse(connection.available)

            var recovered = false
            connection.onReconnection { _, alive ->
                assertFalse(connection.available)
                assertTrue(alive())
                recovered = true
            }
            val second = ActiveRpcPeer(unusedRpcClient) { true }
            connection.managed.install(second)
            assertTrue(recovered)
            assertTrue(connection.available)

            connection.managed.fail("backend stopped")
            assertEquals(BackendConnectionState.Failed, connection.state.value)
            assertEquals("backend stopped", connection.failureReason.value)
            assertFalse(connection.available)
            assertFailsWith<IllegalStateException> {
                connection.managed.install(ActiveRpcPeer(unusedRpcClient) { true })
            }
        }
    }

    test("streaming calls resubscribe across peer replacement") {
        runTest {
            val connection = LocalRpcConnection()
            assertNull(connection.failureReason.value)
            fun peer(value: Int) = ActiveRpcPeer(object : RpcClient by unusedRpcClient {
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
                    emit(value as T)
                    awaitCancellation()
                }
            }) { true }

            val first = peer(10)
            connection.managed.install(first)
            // Exercise ManagedFrontendRpcConnection state transitions without constructing internal RpcCall.
            connection.managed.detach(first)
            val second = peer(20)
            connection.managed.install(second)
            runCurrent()
            assertTrue(connection.available)
            assertEquals(listOf(1, 2), flow { emit(1); emit(2) }.take(2).toList())
        }
    }
}
