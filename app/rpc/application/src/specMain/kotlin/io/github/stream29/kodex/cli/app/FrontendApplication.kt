package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.rpc.RpcFrontendEnvironment
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/** Actual production frontend assembly; no prepared Home handle or backend resource is owned here. */
public expect suspend fun <R> withFrontendApplication(
    connection: FrontendRpcConnection,
    workingDirectory: Path,
    host: FrontendHost,
    environment: RpcFrontendEnvironment,
    applicationWidth: Int? = null,
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R
