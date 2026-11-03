package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CoroutineScope

/** Backend path evaluation with a captured launch base. */
public expect fun createLocalRpcFrontendEnvironment(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): RpcFrontendEnvironment

/** Creates an editor with backend path evaluation. */
public expect fun RpcGlobalEditor(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    applicationScope: CoroutineScope,
): RpcGlobalEditor
