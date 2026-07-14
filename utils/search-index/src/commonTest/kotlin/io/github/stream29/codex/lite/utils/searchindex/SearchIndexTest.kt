package io.github.stream29.codex.lite.utils.searchindex

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals



val searchIndexTest by testSuite {
    test("search returns matching documents") {
        val index = createSearchIndex(
            listOf(
                SearchDocument("calendar", "create calendar event"),
                SearchDocument("filesystem", "read and write local files"),
            ),
        )

        assertEquals(listOf("calendar"), index.search("calendar event", limit = 1))
    }
}
