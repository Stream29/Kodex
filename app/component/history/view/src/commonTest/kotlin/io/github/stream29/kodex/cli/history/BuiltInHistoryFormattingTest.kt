package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.WebSearchAction
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

val builtInHistoryFormattingTest by testSuite {
    test("explicit hosted image references exclude opaque payloads") {
        assertNull("aGVsbG8=".readableHostedImageReference())
        assertNull("data:image/png;base64,aGVsbG8=".readableHostedImageReference())
        assertEquals("https://example.test/a.png", "https://example.test/a.png".readableHostedImageReference())
    }
    test("hosted Web retains all queries and explicit unknown actions have no invented operations") {
        assertEquals(
            listOf("Search first", "Search second", "Search third"),
            WebSearchAction.Search("first", listOf("second", "third")).operationTitles(),
        )
        assertTrue(WebSearchAction.Other.operationTitles().isEmpty())
        assertTrue(WebSearchAction.OpenPage().operationTitles().isEmpty())
    }
}
