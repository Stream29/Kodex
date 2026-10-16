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

/** Borrowed proxies from an already restoring client. This object owns no connection. */
public class RpcServices(client: RpcClient) {
    public val global: GlobalRpc = client.withService()
    public val runtime: AgentRuntimeRpc = client.withService()
    public val index: IndexTimelineRpc = client.withService()
    public val work: WorkTimelineRpc = client.withService()
    public val settings: SettingsTimelineRpc = client.withService()
    public val timestamp: TimestampTimelineRpc = client.withService()
    public val tokenCount: TokenCountTimelineRpc = client.withService()
    public val unstable: UnstableTimelineRpc = client.withService()
}
