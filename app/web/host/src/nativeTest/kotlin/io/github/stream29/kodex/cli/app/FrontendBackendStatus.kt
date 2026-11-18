package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.rpc.control.BackendControlClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun frontendBackendReady(
    endpoint: BackendEndpoint,
    controlClient: BackendControlClient? = null,
): Boolean = try {
    val status = controlClient?.request(endpoint, "status") ?: backendControlRequest(endpoint, "status")
    status["phase"]?.jsonPrimitive?.content == "ready" &&
        status["instance"]?.jsonPrimitive?.content == endpoint.instanceId &&
        status["business_protocol"]?.jsonPrimitive?.content == BackendBusinessProtocol
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { false }
