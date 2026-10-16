package io.github.stream29.kodex.utils.searchindex

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

    test("index owns a snapshot when the caller replaces and clears its list") {
        val documents = mutableListOf(
            SearchDocument("original", "calendar appointment"),
            SearchDocument("other", "filesystem"),
        )
        val index = createSearchIndex(documents)
        documents[0] = SearchDocument("replacement", "calendar appointment")
        assertEquals(listOf("original"), index.search("calendar", limit = 1))
        documents.clear()
        assertEquals(listOf("original"), index.search("calendar", limit = 1))
    }
}
