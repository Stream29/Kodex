package io.github.stream29.kodex.app.web

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.FrontendCapabilityStatus
import io.github.stream29.kodex.backend.FrontendHostCapabilities
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import kotlin.test.assertEquals

internal val hostCapabilitiesTest by testSuite {
    test("backend-owned effects depend on the business connection rather than companion readiness") {
        for (status in FrontendCapabilityStatus.entries) {
            for (backend in BackendConnectionState.entries) {
                val projected = FrontendHostCapabilities(status, status, status, status).settingsCapabilities(backend)
                assertEquals(status == FrontendCapabilityStatus.Ready, projected.directoryBrowsing)
                assertEquals(status == FrontendCapabilityStatus.Ready, projected.contextPathEditing)
                assertEquals(backend == BackendConnectionState.Connected, projected.providerLogin)
                assertEquals(backend == BackendConnectionState.Connected, projected.frontendErrorHooks)
            }
        }
    }
}
