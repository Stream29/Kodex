@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService

public actual class RpcServices actual constructor(client: RpcClient) {
    public actual val global: GlobalRpc = client.withService()
    public actual val runtime: AgentRuntimeRpc = client.withService()
    public actual val storage: AgentStorageRpc = createAgentStorageClient(client)
}
