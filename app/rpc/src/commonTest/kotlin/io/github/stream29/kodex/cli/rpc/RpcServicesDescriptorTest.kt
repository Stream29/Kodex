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
        assertNotNull(services.index)
        assertNotNull(services.work)
        assertNotNull(services.settings)
        assertNotNull(services.timestamp)
        assertNotNull(services.tokenCount)
        assertNotNull(services.unstable)
    }
    test("timeline facades keep original service names identities arguments and Flow calls") {
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
        val timelines = listOf(
            services.index, services.work, services.settings,
            services.timestamp, services.tokenCount, services.unstable,
        )
        for (timeline in timelines) {
            assertEquals(17L, timeline.getCacheNonce(41))
            assertEquals(listOf(17L), timeline.getCacheNonceFlow(41).toList())
        }
        val names = listOf(
            "IndexTimelineRpc", "WorkTimelineRpc", "SettingsTimelineRpc",
            "TimestampTimelineRpc", "TokenCountTimelineRpc", "UnstableTimelineRpc",
        )
        assertEquals(names, calls.chunked(2).map { pair ->
            assertEquals(pair[0].serviceId, pair[1].serviceId)
            assertEquals(listOf("getCacheNonce", "getCacheNonceFlow"), pair.map { it.callableName })
            pair.forEach { assertEquals(listOf(41), it.parameters.toList()) }
            pair[0].descriptor.fqName.removePrefix("io.github.stream29.kodex.rpc.contract.")
        })
        assertEquals(6, calls.map { it.serviceId }.toSet().size)
    }
}
