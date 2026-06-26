package io.github.stream29.codex.lite.utils.ktorclientext

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpHeadersExtensionsTest {
    @Test
    fun extensionHeaderNamesMatchWireNames() {
        assertEquals("originator", HttpHeaders.CodexOriginator)
        assertEquals("ChatGPT-Account-ID", HttpHeaders.ChatGptAccountId)
        assertEquals("version", HttpHeaders.OpenAiSearchVersion)
    }
}
