package io.github.stream29.kodex.app.ui.compose

import androidx.compose.ui.platform.UriHandler
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val assistantMarkdownSafetyTest by testSuite {
    test("only well-formed HTTP scheme prefixes are admitted") {
        listOf("https://example.test/a?b=1", "HTTP://example.test").forEach {
            assertTrue(isSafeAssistantMarkdownUrl(it), it)
        }
        listOf("", "javascript:alert(1)", "file:///tmp/a", "data:text/html,x", "//example.test",
            " https://example.test", "https://", "https:///a", "https://@evil", "https://example.test\\@evil",
            "https://example.test/\nnext").forEach {
            assertFalse(isSafeAssistantMarkdownUrl(it), it)
        }
    }
    test("unsafe links and failed navigation never escape the handler") {
        val opened = mutableListOf<String>()
        val handler = AssistantMarkdownUriHandler(object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                if (uri.endsWith("/fail")) error("private URL must not be logged")
            }
        })
        handler.openUri("javascript:alert(1)")
        handler.openUri("https://example.test/fail")
        handler.openUri("https://example.test/ok")
        assertEquals(listOf("https://example.test/fail", "https://example.test/ok"), opened)
    }
}
