package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.rpc.contract.GlobalRpc

public actual fun createRpcDirectoryPickerBrowser(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): DirectoryPickerBrowser = RpcDirectoryPickerBrowser(rpc, launchBaseDirectory)

public actual fun createRpcContextSourcePathPolicy(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): ContextSourcePathPolicy = RpcContextSourcePathPolicy(rpc, launchBaseDirectory)
