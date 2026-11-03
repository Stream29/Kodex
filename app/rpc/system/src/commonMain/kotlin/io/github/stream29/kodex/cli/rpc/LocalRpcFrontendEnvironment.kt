package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

public actual fun createLocalRpcFrontendEnvironment(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): RpcFrontendEnvironment = object : RpcFrontendEnvironment {
    override val contextPaths: ContextSourcePathPolicy = createRpcContextSourcePathPolicy(rpc, launchBaseDirectory)
}

public actual fun RpcGlobalEditor(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    applicationScope: CoroutineScope,
): RpcGlobalEditor = RpcGlobalEditor(
    global, rpc, applicationScope,
    createLocalRpcFrontendEnvironment(rpc, SystemFileSystem.resolve(Path(".")).toString()),
)
