package io.github.stream29.kodex.app.web

import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import io.github.stream29.kodex.rpc.local.BackendConnectionState

internal fun BackendConnectionState.settingsCapabilities(): SettingsHostCapabilities {
    val connected = this == BackendConnectionState.Connected
    return SettingsHostCapabilities(
        directoryBrowsing = connected,
        contextPathEditing = connected,
        providerLogin = connected,
        frontendErrorHooks = connected,
    )
}
