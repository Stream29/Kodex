package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService

internal actual fun createAgentStorageClient(client: RpcClient): AgentStorageRpc = client.withService()
