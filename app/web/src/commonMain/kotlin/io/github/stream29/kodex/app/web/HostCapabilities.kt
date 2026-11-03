package io.github.stream29.kodex.app.web

import io.github.stream29.kodex.backend.FrontendCapabilityStatus
import io.github.stream29.kodex.backend.FrontendHostCapabilities
import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import io.github.stream29.kodex.rpc.local.BackendConnectionState

internal fun FrontendHostCapabilities.settingsCapabilities(
    backend: BackendConnectionState,
): SettingsHostCapabilities = SettingsHostCapabilities(
    directoryBrowsing = directoryBrowsing == FrontendCapabilityStatus.Ready,
    contextPathEditing = contextPathEvaluation == FrontendCapabilityStatus.Ready,
    providerLogin = backend == BackendConnectionState.Connected,
    frontendErrorHooks = backend == BackendConnectionState.Connected,
)
