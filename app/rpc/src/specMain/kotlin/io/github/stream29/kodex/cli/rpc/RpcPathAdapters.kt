package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.rpc.contract.GlobalRpc

/** Captures the initial CLI/welcome base, not the daemon cwd or a mutable Session directory. */
public expect fun createRpcDirectoryPickerBrowser(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): DirectoryPickerBrowser

/** Read-only backend environment evaluation; duplicate selection and mutation stay in the VM. */
public expect fun createRpcContextSourcePathPolicy(
    rpc: GlobalRpc,
    launchBaseDirectory: String,
): ContextSourcePathPolicy
