package io.github.stream29.codex.lite.utils.ktorclientext

import de.infix.testBalloon.framework.core.testSuite

import io.ktor.http.HttpHeaders
import kotlin.test.assertEquals



val httpHeadersExtensionsTest by testSuite {
    test("extension header names match wire names") {
        assertEquals("originator", HttpHeaders.CodexOriginator)
        assertEquals("ChatGPT-Account-ID", HttpHeaders.ChatGptAccountId)
        assertEquals("version", HttpHeaders.OpenAiSearchVersion)
    }
}
