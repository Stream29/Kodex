package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val openAiClientConfigTest by testSuite {
    test("uses the Kodex-maintained compatible API client version") {
        val config = OpenAiClientConfig()

        assertEquals(KodexCompatibleApiClientVersion, config.clientVersion)
        assertEquals(
            "codex_cli_rs/$KodexCompatibleApiClientVersion (Kodex)",
            config.userAgent,
        )
    }
}
