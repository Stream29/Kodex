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
        assertEquals(300_000, config.sseSocketTimeoutMillis)
        assertEquals(2, config.remoteCompactionMaxRetries)
    }

    test("rejects invalid streaming timeout configuration") {
        assertFailsWith<IllegalArgumentException> {
            OpenAiClientConfig(sseSocketTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAiClientConfig(remoteCompactionMaxRetries = -1)
        }
    }
}
