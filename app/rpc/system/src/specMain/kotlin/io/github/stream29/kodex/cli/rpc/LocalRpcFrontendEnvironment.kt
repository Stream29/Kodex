package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CoroutineScope

/** Native frontend-home paths and loopback OAuth; never an implicit browser capability. */
public expect val LocalRpcFrontendEnvironment: RpcFrontendEnvironment

/** Creates an editor with the Native frontend's environment and callback ownership. */
public expect fun RpcGlobalEditor(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    applicationScope: CoroutineScope,
): RpcGlobalEditor

/** Browser URLs and callback payloads live only for the attempt; neither is logged. */
public expect suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    mcpRedirectUri: String? = null,
): KodexAuthLoginAttempt
