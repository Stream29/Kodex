package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val openAiClientConfigTest by testSuite {
    test("uses the Kodex-maintained compatible API client version") {
        val config = OpenAiClientConfig()

        assertEquals(KodexCompatibleApiClientVersion, config.clientVersion)
        assertEquals(
            "codex_cli_rs/$KodexCompatibleApiClientVersion (Kodex)",
            config.userAgent,
        )
        assertEquals(90_000, config.requestTimeoutMillis)
        assertEquals(600_000, config.imageRequestTimeoutMillis)
        assertEquals(600_000, config.sseSocketTimeoutMillis)
        assertEquals(20, config.remoteCompactionMaxRetries)
    }

    test("rejects invalid streaming timeout configuration") {
        assertFailsWith<IllegalArgumentException> {
            OpenAiClientConfig(sseSocketTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAiClientConfig(remoteCompactionMaxRetries = -1)
        }
    }

    test("image timeout is independent of ordinary and streaming timeouts") {
        val config = OpenAiClientConfig(imageRequestTimeoutMillis = 120_000)
        assertEquals(120_000, config.imageRequestTimeoutMillis)
        assertEquals(90_000, config.requestTimeoutMillis)
        assertEquals(600_000, config.sseSocketTimeoutMillis)
        assertEquals(OpenAiClientRetryConfig(), config.retry)
    }

    test("rejects non-positive image timeout configuration") {
        for (timeout in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                OpenAiClientConfig(imageRequestTimeoutMillis = timeout)
            }
        }
    }
}
