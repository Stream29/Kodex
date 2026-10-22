package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private const val CompatibleApiClientVersion: String = KodexCompatibleApiClientVersion

val openAiClientContractConstantsTest by testSuite {
    test("API compatibility version remains a compile-time constant") {
        assertEquals(KodexCompatibleApiClientVersion, CompatibleApiClientVersion)
        assertEquals(CompatibleApiClientVersion, OpenAiClientConfig().clientVersion)
    }
}
