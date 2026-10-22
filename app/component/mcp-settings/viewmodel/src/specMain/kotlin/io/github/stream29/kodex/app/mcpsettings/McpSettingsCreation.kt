package io.github.stream29.kodex.app.mcpsettings

import kotlinx.coroutines.CoroutineScope

/** Creates an [McpSettingsViewModel] without constructing a backend or shared client. */
public expect fun createMcpSettingsViewModel(
    dependencies: McpSettingsDependencies,
    ownerScope: CoroutineScope,
): McpSettingsViewModel
