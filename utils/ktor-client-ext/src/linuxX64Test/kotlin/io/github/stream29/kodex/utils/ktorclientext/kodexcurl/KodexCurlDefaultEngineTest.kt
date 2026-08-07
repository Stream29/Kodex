package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

    @Test
    public fun connectionFailureIsAnIoException(): Unit = runBlocking {
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
