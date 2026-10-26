package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService
import kotlin.test.assertNotNull

val productionServiceDescriptorTest by testSuite {
    val factories: List<Pair<String, (RpcClient) -> Any>> = listOf(
        "GlobalRpc" to { it.withService<GlobalRpc>() },
        "AgentRuntimeRpc" to { it.withService<AgentRuntimeRpc>() },
        "IndexTimelineRpc" to { it.withService<IndexTimelineRpc>() },
        "WorkTimelineRpc" to { it.withService<WorkTimelineRpc>() },
        "SettingsTimelineRpc" to { it.withService<SettingsTimelineRpc>() },
        "TimestampTimelineRpc" to { it.withService<TimestampTimelineRpc>() },
        "TokenCountTimelineRpc" to { it.withService<TokenCountTimelineRpc>() },
        "UnstableTimelineRpc" to { it.withService<UnstableTimelineRpc>() },
    )
    for ((name, create) in factories) {
        test("$name production interface has a generated client descriptor") {
            withInMemoryRpc({}) { client -> assertNotNull(create(client)) }
        }
    }
}
