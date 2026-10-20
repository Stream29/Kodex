package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

val backendProtocolTest by testSuite {
    test("strict terminal waits advance only the business checkpoint and keep management protocol one") {
        assertEquals("0.5.2", BackendBusinessProtocol)
        assertNotEquals("0.5.0", BackendBusinessProtocol)
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
