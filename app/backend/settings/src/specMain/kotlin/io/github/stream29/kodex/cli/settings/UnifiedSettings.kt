package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings

/** Backend-owned single-file snapshot. RPC exposes projections, not this whole storage object. */
public data class UnifiedSettings(
    public val backend: BackendSettings,
    public val frontend: CliFrontendSettings,
)
