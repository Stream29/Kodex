@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import kotlinx.coroutines.flow.StateFlow

public actual class KodexApplication actual constructor(
    public actual val viewModel: ApplicationViewModel,
    public actual val newLineKey: StateFlow<NewLineKey>,
    public actual val sidebarSettings: SidebarSettingsViewModel,
    public actual val reportUnhandledError: (Throwable) -> Unit,
    public actual val reportWarning: (String) -> Unit,
    public actual val connectionState: StateFlow<BackendConnectionState>,
    public actual val connectionFailureReason: StateFlow<String?>,
)
