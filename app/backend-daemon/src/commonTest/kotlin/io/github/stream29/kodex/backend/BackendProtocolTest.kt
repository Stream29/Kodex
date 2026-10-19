package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

val backendProtocolTest by testSuite {
    test("cwd-only creation advances only the business checkpoint and keeps management protocol one") {
        assertEquals("0.5.0", BackendBusinessProtocol)
        assertNotEquals("0.4.15", BackendBusinessProtocol)
        assertEquals(1, BackendControlProtocol)
        val endpoint = BackendEndpoint(
            instanceId = "fixture-instance",
            pid = 1,
            port = 12345,
            password = "x".repeat(32),
            applicationVersion = "unrelated-app-build",
        )
        assertEquals(BackendBusinessProtocol, endpoint.businessProtocol)
        assertEquals(1, endpoint.controlProtocol)
        assertEquals("unrelated-app-build", endpoint.applicationVersion)
    }
}
