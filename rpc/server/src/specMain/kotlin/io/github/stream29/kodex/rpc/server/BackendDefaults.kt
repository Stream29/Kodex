package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.BackendSettings

/** Same baseline values as the original settings, without its frontend or control-Hook fields. */
public expect fun defaultBackendSettings(): BackendSettings
