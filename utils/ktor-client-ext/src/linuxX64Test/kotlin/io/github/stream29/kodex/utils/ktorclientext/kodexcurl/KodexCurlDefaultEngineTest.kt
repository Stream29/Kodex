package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSocketCapability
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

public class KodexCurlDefaultEngineTest {
    @Test
    public fun defaultHttpClientUsesKodexCurl() {
        val client = HttpClient()
        try {
            assertIs<KodexCurlClientEngine>(client.engine)
            assertTrue(WebSocketCapability in client.engine.supportedCapabilities)
        } finally {
            client.close()
        }
    }
}
