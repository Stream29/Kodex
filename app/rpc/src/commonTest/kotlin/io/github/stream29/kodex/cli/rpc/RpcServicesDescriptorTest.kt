@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

val rpcServicesDescriptorTest by testSuite {
    test("real frontend service assembly resolves all production descriptors without making calls") {
        val client = object : RpcClient {
            override suspend fun <T> call(call: RpcCall): T = error("Proxy construction must not call RPC.")
            override fun <T> callServerStreaming(call: RpcCall): Flow<T> = error("Proxy construction must not subscribe.")
        }
        val services = RpcServices(client)
        assertNotNull(services.global)
        assertNotNull(services.runtime)
        assertNotNull(services.storage)
    }
    test("storage facade keeps original service name identity arguments and Flow calls") {
        val calls = mutableListOf<RpcCall>()
        val client = object : RpcClient {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T> call(call: RpcCall): T {
                calls += call
                return 17L as T
            }
            @Suppress("UNCHECKED_CAST")
            override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                calls += call
                return flowOf(17L as T)
            }
        }
        val services = RpcServices(client)
        assertEquals(0, calls.size)
        assertEquals(17L, services.storage.getCacheNonce(41))
        assertEquals(listOf(17L), services.storage.getCacheNonceFlow(41).toList())
        assertEquals(calls[0].serviceId, calls[1].serviceId)
        assertEquals(listOf("getCacheNonce", "getCacheNonceFlow"), calls.map { it.callableName })
        calls.forEach { assertEquals(listOf(41), it.parameters.toList()) }
        assertEquals(
            "AgentStorageRpc",
            calls[0].descriptor.fqName.removePrefix("io.github.stream29.kodex.rpc.contract."),
        )
    }
}
