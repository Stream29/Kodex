package io.github.stream29.kodex.app.web

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import kotlin.test.assertEquals

internal val hostCapabilitiesTest by testSuite {
    test("Web settings capabilities require the live business connection") {
        for (backend in BackendConnectionState.entries) {
            val projected = backend.settingsCapabilities()
            val connected = backend == BackendConnectionState.Connected
            assertEquals(connected, projected.directoryBrowsing)
            assertEquals(connected, projected.contextPathEditing)
            assertEquals(connected, projected.providerLogin)
            assertEquals(connected, projected.frontendErrorHooks)
        }
    }
}
