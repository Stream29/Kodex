@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.contract.IndexTimelineRpc
import io.github.stream29.kodex.rpc.contract.SettingsTimelineRpc
import io.github.stream29.kodex.rpc.contract.TimestampTimelineRpc
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.contract.UnstableTimelineRpc
import io.github.stream29.kodex.rpc.contract.WorkTimelineRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService

public actual class RpcServices actual constructor(client: RpcClient) {
    public actual val global: GlobalRpc = client.withService()
    public actual val runtime: AgentRuntimeRpc = client.withService()
    public actual val index: IndexTimelineRpc = client.withService()
    public actual val work: WorkTimelineRpc = client.withService()
    public actual val settings: SettingsTimelineRpc = client.withService()
    public actual val timestamp: TimestampTimelineRpc = client.withService()
    public actual val tokenCount: TokenCountTimelineRpc = client.withService()
    public actual val unstable: UnstableTimelineRpc = client.withService()
}
