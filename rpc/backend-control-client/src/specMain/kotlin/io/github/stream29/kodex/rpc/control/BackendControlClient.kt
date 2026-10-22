package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendEndpoint
import kotlinx.serialization.json.JsonObject

/** Management CLI/launch bootstrap only. No business DTO or TUI recovery dependency. */
public expect suspend fun backendControlRequest(endpoint: BackendEndpoint, action: String): JsonObject
