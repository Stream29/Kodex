package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.request.get
import kotlinx.io.IOException
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

val kodexCurlDefaultEngineTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("defaultHttpClientUsesKodexCurl") {
        val client = HttpClient()
        try {
            assertIs<KodexCurlClientEngine>(client.engine)
            assertTrue(WebSocketCapability in client.engine.supportedCapabilities)
        } finally {
            client.close()
        }
    }

    test("connectionFailureIsAnIoException") {
        val client = HttpClient(KodexCurl) {
            install(HttpTimeout) {
                connectTimeoutMillis = 1_000
            }
        }
        try {
            assertFailsWith<IOException> {
                client.get("http://127.0.0.1:1/")
            }
        } finally {
            client.close()
        }
    }
}
