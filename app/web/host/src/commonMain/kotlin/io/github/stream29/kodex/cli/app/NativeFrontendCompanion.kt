package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.FrontendCapabilityStatus
import io.github.stream29.kodex.backend.FrontendHostCapabilities
import io.github.stream29.kodex.rpc.control.BackendControlClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
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

/** The owner retains pairing and lease only; OAuth and error Hooks belong to the backend. */
internal suspend fun acquireNativeFrontendCompanion(scope: CoroutineScope): FrontendCompanionResources {
    scope.coroutineContext.ensureActive()
    val closed = MutableStateFlow(false)
    return object : FrontendCompanionResources {
        private fun active(): Boolean = !closed.value && scope.coroutineContext[Job]?.isActive == true

        override val capabilities: FrontendHostCapabilities
            get() {
                val status = if (active()) FrontendCapabilityStatus.Ready else FrontendCapabilityStatus.OwnerClosed
                return FrontendHostCapabilities(status, status)
            }

        override suspend fun closeAndJoin() { closed.value = true }
    }
}
