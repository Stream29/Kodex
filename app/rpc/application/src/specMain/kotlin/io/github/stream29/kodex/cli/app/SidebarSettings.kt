package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import kotlinx.coroutines.CoroutineScope

/** Binds sidebar presentation to the existing settings and caller-owned scope. */
public expect fun createSidebarSettingsViewModel(
    global: RpcGlobalSettings,
    scope: CoroutineScope,
    initialized: Boolean,
): SidebarSettingsViewModel
