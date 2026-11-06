package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import kotlinx.rpc.RpcClient

internal expect fun createAgentStorageClient(client: RpcClient): AgentStorageRpc
